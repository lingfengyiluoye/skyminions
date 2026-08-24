package com.hcs.minions.service;

import com.hcs.minions.config.CollectionConfig;
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collection 资源累计 + 里程碑系统（Hypixel SkyBlock 经济底座）。
 *
 * <p>记录每个玩家每种资源的累计产出量；跨过 {@link CollectionConfig} 阈值时
 * 触发里程碑：金币奖励（Vault 异步入账）+ 里程碑槽位加成（放置仆从时叠加到上限）。
 * 持久化到 collection.yml（累计量 + 已达成里程碑序号），写入走内存合并，
 * 落盘由外部定期触发。</p>
 */
public final class CollectionService {

    private final JavaPlugin plugin;
    private final File file;
    private final CollectionConfig cfg;
    private final EconomyService economy;

    /** 玩家 -> 资源 -> 累计量。 */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> collections = new ConcurrentHashMap<>();

    /** 玩家 -> 资源 -> 已触发（已领奖）的最高里程碑序号（1-based，缺省视为 0）。 */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Integer>> claimed = new ConcurrentHashMap<>();

    public CollectionService(JavaPlugin plugin, CollectionConfig cfg, EconomyService economy) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "collection.yml");
    }

    /** 启动时加载 collection.yml（不存在则空启动）。 */
    public void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration store = YamlConfiguration.loadConfiguration(file);
        loadSection(store.getConfigurationSection("totals"), collections);
        // 兼容旧版顶层 <uuid>.<material> 格式（无 totals 前缀），仅在未读到新格式时回退
        if (collections.isEmpty()) {
            for (String key : store.getKeys(false)) {
                if ("claimed".equals(key)) {
                    continue;
                }
                ConfigurationSection legacy = store.getConfigurationSection(key);
                if (legacy == null) {
                    continue;
                }
                ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
                for (String mat : legacy.getKeys(false)) {
                    map.put(mat, legacy.getLong(mat));
                }
                collections.put(parseUuid(key), map);
            }
        }
        loadClaimed(store.getConfigurationSection("claimed"));
        Logs.info("Collection 已加载 {} 个玩家记录", collections.size());
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
            ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
            for (String mat : sec.getKeys(false)) {
                map.put(mat, sec.getLong(mat));
            }
            into.put(parseUuid(uuid), map);
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
            ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
            for (String mat : sec.getKeys(false)) {
                map.put(mat, sec.getInt(mat));
            }
            claimed.put(parseUuid(uuid), map);
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            Logs.warn("collection.yml 存在非法 UUID: {}", raw);
            return new UUID(0, 0);
        }
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
        if (!cfg.enabled()) {
            return;
        }
        ConcurrentHashMap<String, Integer> cm = claimed.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        // compute 原子保护：同一玩家同一资源的并发产出不会漏发/重发里程碑
        cm.compute(material.name(), (k, oldMax) -> {
            int reached = cfg.reachedIndex(total);
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
        Player online = Bukkit.getPlayer(owner);
        for (int n = from; n <= to; n++) {
            long coins = cfg.coinsBase() * n;
            if (economy != null && economy.isEnabled() && coins > 0) {
                economy.depositCents(owner, coins * 100);
            }
            if (online != null) {
                online.sendMessage(Messages.milestoneReached(
                        MaterialNames.of(material), cfg.thresholdOf(n), coins, cfg.slotMilestones().contains(n)));
            }
            Logs.info("Collection 里程碑: player={}, resource={}, milestone={}/{}",
                    owner, material.name(), n, cfg.milestones().length);
        }
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
        for (long m : cfg.milestones()) {
            if (total < m) {
                return m;
            }
        }
        return 0;
    }

    /** 里程碑奖励的仆从槽位加成（多种资源可叠加，总量受 max-bonus-slots 硬上限钳制）。 */
    public int bonusSlots(UUID owner) {
        if (!cfg.enabled()) {
            return 0;
        }
        ConcurrentHashMap<String, Integer> cm = claimed.get(owner);
        if (cm == null || cm.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (int max : cm.values()) {
            total += cfg.bonusSlotsFor(max);
        }
        return (int) Math.min(total, cfg.maxBonusSlots());
    }

    /** 落盘 collection.yml。 */
    public void save() {
        YamlConfiguration store = new YamlConfiguration();
        for (Map.Entry<UUID, ConcurrentHashMap<String, Long>> e : collections.entrySet()) {
            String uuid = e.getKey().toString();
            for (Map.Entry<String, Long> m : e.getValue().entrySet()) {
                store.set("totals." + uuid + "." + m.getKey(), m.getValue());
            }
        }
        for (Map.Entry<UUID, ConcurrentHashMap<String, Integer>> e : claimed.entrySet()) {
            String uuid = e.getKey().toString();
            for (Map.Entry<String, Integer> m : e.getValue().entrySet()) {
                store.set("claimed." + uuid + "." + m.getKey(), m.getValue());
            }
        }
        try {
            store.save(file);
        } catch (IOException ex) {
            Logs.error("保存 collection.yml 失败", ex);
        }
    }
}
