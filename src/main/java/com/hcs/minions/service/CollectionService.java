package com.hcs.minions.service;

import com.hcs.minions.config.CollectionConfig;
import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Messages;
import com.hcs.minions.util.PlayerTasks;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collection 资源累计 + 里程碑系统（Hypixel SkyBlock 经济底座）。
 *
 * <p>记录每个玩家每种资源的累计产出量；跨过 {@link CollectionConfig} 阈值时
 * 触发里程碑：金币奖励（Vault 异步入账）+ 里程碑槽位加成（放置仆从时叠加到上限）。</p>
 *
 * <p><b>持久化：按玩家分片 + 脏标记增量落盘。</b>旧实现把所有玩家的全部资源写进单个
 * collection.yml，每 60 秒整文件重写——千人服就是每分钟重写几十万节点，重启时整读。
 * 现在每个玩家一个 {@code collections/<uuid>.yml}，只有数据变过的玩家会被重写；
 * 首次启动遇到旧版 collection.yml 时自动导入分片（不丢数据）。</p>
 *
 * <p>写入走虚拟线程（文件 IO 不阻塞主线程），内存合并仍在调用方线程完成。</p>
 */
public final class CollectionService {

    private final JavaPlugin plugin;
    private final ConfigProvider config;
    private final EconomyService economy;
    private final AsyncExecutor async;
    /** 分片目录：plugin 数据目录下的 collections/。 */
    private final File dir;
    /** 旧版单文件（仅用于首次导入）。 */
    private final File legacyFile;

    /** 玩家 -> 资源 -> 累计量。 */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> collections = new ConcurrentHashMap<>();

    /** 玩家 -> 资源 -> 已触发（已领奖）的最高里程碑序号（1-based，缺省视为 0）。 */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Integer>> claimed = new ConcurrentHashMap<>();

    /** 玩家 -> 已缴纳解锁金币的仆从类型 key（只收一次，见 PermissionService 解锁流程）。 */
    private final ConcurrentHashMap<UUID, Set<String>> paidUnlocks = new ConcurrentHashMap<>();

    /** 待落盘的玩家（脏标记）：save 只重写这些分片。 */
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public CollectionService(JavaPlugin plugin, ConfigProvider config, EconomyService economy,
                             AsyncExecutor async) {
        this.plugin = plugin;
        this.config = config;
        this.economy = economy;
        this.async = async;
        this.dir = new File(plugin.getDataFolder(), "collections");
        this.legacyFile = new File(plugin.getDataFolder(), "collection.yml");
    }

    /** 启动时加载：优先分片目录；遇到旧版 collection.yml 则导入后标记全部脏。 */
    public void load() {
        boolean imported = false;
        if (legacyFile.exists()) {
            imported = importLegacy();
        }
        int shards = 0;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                UUID id = parseUuid(f.getName().substring(0, f.getName().length() - 4));
                if (id == null) {
                    continue;
                }
                if (loadShard(id, f)) {
                    shards++;
                }
            }
        }
        if (imported) {
            // 旧数据已在内存中：全部标记脏，下一次 save 会写成分片
            dirty.addAll(collections.keySet());
            dirty.addAll(claimed.keySet());
            Logs.info("已从旧版 collection.yml 导入 {} 个玩家记录，将在下次保存时写入分片", collections.size());
        }
        Logs.info("Collection 已加载 {} 个玩家记录（分片目录 {}）", shards > 0 ? shards : collections.size(),
                dir.getName());
    }

    /** 导入旧版单文件（新旧两种格式都兼容）。 */
    private boolean importLegacy() {
        try {
            YamlConfiguration store = YamlConfiguration.loadConfiguration(legacyFile);
            loadSection(store.getConfigurationSection("totals"), collections);
            // 兼容旧版顶层 <uuid>.<material> 格式（无 totals 前缀），仅在未读到新格式时回退
            if (collections.isEmpty()) {
                for (String key : store.getKeys(false)) {
                    if ("claimed".equals(key) || "paid".equals(key)) {
                        continue;
                    }
                    ConfigurationSection legacy = store.getConfigurationSection(key);
                    if (legacy == null) {
                        continue;
                    }
                    UUID id = parseUuid(key);
                    if (id == null) {
                        continue; // 非法 UUID 跳过：旧实现回退全零 UUID 会把多个脏键合并成伪玩家
                    }
                    ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
                    for (String mat : legacy.getKeys(false)) {
                        map.put(mat, legacy.getLong(mat));
                    }
                    collections.put(id, map);
                }
            }
            loadClaimed(store.getConfigurationSection("claimed"));
            loadPaid(store.getConfigurationSection("paid"));
            return !collections.isEmpty() || !claimed.isEmpty();
        } catch (Exception e) {
            Logs.error("旧版 collection.yml 导入失败（将继续使用分片目录）", e);
            return false;
        }
    }

    /** 读取单个玩家分片。 */
    private boolean loadShard(UUID id, File file) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            loadSection(yaml.getConfigurationSection("totals"), collections, id);
            loadClaimed(yaml.getConfigurationSection("claimed"), id);
            loadPaid(yaml.getStringList("paid"), id);
            return true;
        } catch (Exception e) {
            Logs.error("读取 collection 分片失败（已跳过）: {}", file.getName(), e);
            return false;
        }
    }

    private static void loadSection(ConfigurationSection root,
                                    ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> into) {
        if (root == null) {
            return;
        }
        for (String uuid : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(uuid);
            if (sec == null) {
                continue;
            }
            UUID id = parseUuid(uuid);
            if (id == null) {
                continue;
            }
            ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
            for (String mat : sec.getKeys(false)) {
                map.put(mat, sec.getLong(mat));
            }
            into.put(id, map);
        }
    }

    private static void loadSection(ConfigurationSection root,
                                    ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> into, UUID id) {
        if (root == null) {
            return;
        }
        ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
        for (String mat : root.getKeys(false)) {
            map.put(mat, root.getLong(mat));
        }
        if (!map.isEmpty()) {
            into.put(id, map);
        }
    }

    private void loadClaimed(ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (String uuid : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(uuid);
            if (sec == null) {
                continue;
            }
            UUID id = parseUuid(uuid);
            if (id == null) {
                continue;
            }
            loadClaimed(sec, id);
        }
    }

    private void loadClaimed(ConfigurationSection sec, UUID id) {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        for (String mat : sec.getKeys(false)) {
            map.put(mat, sec.getInt(mat));
        }
        if (!map.isEmpty()) {
            claimed.put(id, map);
        }
    }

    private void loadPaid(ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (String uuid : root.getKeys(false)) {
            UUID id = parseUuid(uuid);
            if (id != null) {
                loadPaid(root.getStringList(uuid), id);
            }
        }
    }

    private void loadPaid(List<String> keys, UUID id) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<String> set = ConcurrentHashMap.newKeySet();
        set.addAll(keys);
        paidUnlocks.put(id, set);
    }

    /** 解析 UUID；非法值告警并返回 null（调用方必须跳过该键，不得合并到伪玩家）。 */
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            Logs.warn("collection 数据存在非法 UUID，已跳过该记录: {}", raw);
            return null;
        }
    }

    /** 当前生效的 Collection 配置快照（热重载安全）。 */
    private CollectionConfig cfg() {
        return config.get().collections();
    }

    /**
     * 累计一次产出（内存合并，线程安全），并检测跨阈值触发里程碑。
     * 可在 region 线程调用：金币入账走 Vault 异步，消息用 Adventure（线程安全）。
     */
    public void record(UUID owner, Material material, long amount) {
        if (owner == null || material == null || amount <= 0) {
            return;
        }
        ConcurrentHashMap<String, Long> map = collections.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        long total = map.merge(material.name(), amount, Long::sum);
        dirty.add(owner);
        if (!cfg().enabled()) {
            return;
        }
        ConcurrentHashMap<String, Integer> cm = claimed.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        // compute 原子保护：同一玩家同一资源的并发产出不会漏发/重发里程碑
        cm.compute(material.name(), (k, oldMax) -> {
            int reached = cfg().reachedIndex(total);
            int old = oldMax == null ? 0 : oldMax;
            if (reached > old) {
                award(owner, material, old + 1, reached);
                return reached;
            }
            return old;
        });
    }

    /** 发放第 from..to 个里程碑奖励（金币 + 槽位加成提示）。 */
    private void award(UUID owner, Material material, int from, int to) {
        for (int n = from; n <= to; n++) {
            long coins = cfg().coinsBase() * n;
            if (economy != null && economy.isEnabled() && coins > 0) {
                economy.depositCents(owner, coins * 100);
            }
            int milestone = n;
            PlayerTasks.run(plugin, owner, online -> {
                online.sendMessage(Messages.milestoneReached(
                        MaterialNames.of(material), cfg().thresholdOf(milestone), coins,
                        cfg().slotMilestones().contains(milestone)));
                // 里程碑是长期目标的兑现时刻：消息 + 升级音效双通道确认
                com.hcs.minions.util.Sounds.milestone(online);
            });
            Logs.info("Collection 里程碑: player={}, resource={}, milestone={}/{}",
                    owner, material.name(), n, cfg().milestones().length);
        }
        dirty.add(owner);
    }

    /** 某玩家的全部资源累计快照。 */
    public Map<String, Long> of(UUID owner) {
        ConcurrentHashMap<String, Long> map = collections.get(owner);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    /** 某玩家某资源的累计量。 */
    public long get(UUID owner, Material material) {
        ConcurrentHashMap<String, Long> map = collections.get(owner);
        return map == null ? 0L : map.getOrDefault(material.name(), 0L);
    }

    /**
     * 某玩家的进度快照：资源 -> [累计量, 下一里程碑阈值]（0 = 已全部达成）。
     * 按累计量降序，供 /minion collection 展示。
     */
    public Map<String, long[]> progressOf(UUID owner) {
        ConcurrentHashMap<String, Long> map = collections.get(owner);
        if (map == null) {
            return Map.of();
        }
        Map<String, long[]> out = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> out.put(e.getKey(), new long[]{e.getValue(), nextThreshold(e.getValue())}));
        return out;
    }

    /** 下一里程碑阈值；全部达成返回 0。 */
    private long nextThreshold(long total) {
        for (long m : cfg().milestones()) {
            if (total < m) {
                return m;
            }
        }
        return 0;
    }

    /**
     * 某玩家某资源距离下一里程碑的进度（GUI 进度条用，同维度）。
     *
     * @return {@code [当前累计量, 下一里程碑阈值]}；已全部达成时阈值为 0（调用方不画条）
     */
    public long[] milestoneProgress(UUID owner, Material material) {
        long total = get(owner, material);
        return new long[]{total, nextThreshold(total)};
    }

    /** 里程碑奖励的仆从槽位加成（多种资源可叠加，总量受 max-bonus-slots 硬上限钳制）。 */
    public int bonusSlots(UUID owner) {
        if (!cfg().enabled()) {
            return 0;
        }
        ConcurrentHashMap<String, Integer> cm = claimed.get(owner);
        if (cm == null || cm.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (int max : cm.values()) {
            total += cfg().bonusSlotsFor(max);
        }
        return (int) Math.min(total, cfg().maxBonusSlots());
    }

    // ------------------------------------------------------------------
    // 解锁金币（只收一次）
    // ------------------------------------------------------------------

    /** 该玩家是否已为指定类型缴过解锁金币。 */
    public boolean hasPaidUnlock(UUID owner, String typeKey) {
        Set<String> paid = paidUnlocks.get(owner);
        return paid != null && paid.contains(typeKey);
    }

    /** 标记指定类型的解锁金币已缴纳（调用方负责扣款）。 */
    public void markPaidUnlock(UUID owner, String typeKey) {
        paidUnlocks.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(typeKey);
        dirty.add(owner);
    }

    // ------------------------------------------------------------------
    // 落盘（按玩家分片 + 脏标记增量）
    // ------------------------------------------------------------------

    /**
     * 落盘：只重写数据变过的玩家分片（异步，虚拟线程）。
     *
     * <p>旧实现每分钟重写整个 collection.yml；现在写入量正比于「本分钟有多少玩家
     * 有产出」，与总玩家数解耦。分片写失败时保留脏标记，下一轮重试，不丢数据。</p>
     */
    public void save() {
        List<UUID> pending = new ArrayList<>(dirty);
        if (pending.isEmpty()) {
            return;
        }
        dirty.removeAll(pending);
        async.run(() -> {
            if (!dir.exists() && !dir.mkdirs()) {
                Logs.error("无法创建 collection 分片目录: {}", dir.getAbsolutePath());
                dirty.addAll(pending); // 下一轮重试
                return;
            }
            for (UUID id : pending) {
                try {
                    writeShard(id);
                } catch (Exception e) {
                    Logs.error("写入 collection 分片失败，保留脏标记待重试: {}", id, e);
                    dirty.add(id);
                }
            }
        });
    }

    /** 同步落盘（onDisable 路径）：仍在虚拟线程池外由调用方保证顺序。 */
    public void saveNowBlocking() {
        List<UUID> pending = new ArrayList<>(dirty);
        dirty.removeAll(pending);
        if (!dir.exists() && !dir.mkdirs()) {
            Logs.error("无法创建 collection 分片目录: {}", dir.getAbsolutePath());
            return;
        }
        for (UUID id : pending) {
            try {
                writeShard(id);
            } catch (Exception e) {
                Logs.error("关闭时写入 collection 分片失败: {}", id, e);
            }
        }
    }

    /** 写单个玩家分片（原子替换：先写临时文件再 move，避免半截文件）。 */
    private void writeShard(UUID id) throws IOException {
        Path target = new File(dir, id + ".yml").toPath();
        Path tmp = new File(dir, id + ".yml.tmp").toPath();
        YamlConfiguration yaml = new YamlConfiguration();
        ConcurrentHashMap<String, Long> totals = collections.get(id);
        if (totals != null) {
            for (Map.Entry<String, Long> e : totals.entrySet()) {
                yaml.set("totals." + e.getKey(), e.getValue());
            }
        }
        ConcurrentHashMap<String, Integer> cm = claimed.get(id);
        if (cm != null) {
            for (Map.Entry<String, Integer> e : cm.entrySet()) {
                yaml.set("claimed." + e.getKey(), e.getValue());
            }
        }
        Set<String> paid = paidUnlocks.get(id);
        if (paid != null && !paid.isEmpty()) {
            yaml.set("paid", List.copyOf(paid));
        }
        Files.writeString(tmp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
