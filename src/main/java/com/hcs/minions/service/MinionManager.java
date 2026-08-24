package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.event.MinionPlacedEvent;
import com.hcs.minions.event.MinionRemovedEvent;
import com.hcs.minions.model.BlockLocation;
import com.hcs.minions.model.Minion;
import com.hcs.minions.repository.MinionRepository;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Messages;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import com.hcs.minions.work.WorkStrategyRegistry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 仆从管理器：全局单调度器 + 工作编排。
 * 掉落 {@code Inventory#addItem} 到真实仓库（主线程），仅 Vault 售卖异步。
 * 盔甲架小人按需生成、随区块卸载自愈（stand.isValid()）。
 */
public final class MinionManager {

    private final JavaPlugin plugin;
    private final ConfigProvider config;
    private final MinionRepository repository;
    private final WorkStrategyRegistry strategies;
    private final BlockSearcher searcher;
    private final MinionEntityService entities;
    private final SellService sell;
    private final SkyblockHook skyblock;
    private final PermissionService permissions;
    private final AsyncExecutor async;
    private final UpgradeService upgrades;
    private final CollectionService collection;

    private final ConcurrentHashMap<UUID, Minion> minions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockLocation, UUID> byLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastDebugLog = new ConcurrentHashMap<>();
    /** 主人 -> 仆从数 索引，O(1) 查询（替代全量 stream 计数）。 */
    private final ConcurrentHashMap<UUID, Integer> ownerCount = new ConcurrentHashMap<>();

    private ScheduledTask tickTask;
    private ScheduledTask sellTask;

    public MinionManager(JavaPlugin plugin, ConfigProvider config, MinionRepository repository,
                         WorkStrategyRegistry strategies, BlockSearcher searcher, MinionEntityService entities,
                         SellService sell, SkyblockHook skyblock, PermissionService permissions, AsyncExecutor async,
                         UpgradeService upgrades, CollectionService collection) {
        this.plugin = plugin;
        this.config = config;
        this.repository = repository;
        this.strategies = strategies;
        this.searcher = searcher;
        this.entities = entities;
        this.sell = sell;
        this.skyblock = skyblock;
        this.permissions = permissions;
        this.async = async;
        this.upgrades = upgrades;
        this.collection = collection;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public void start() {
        repository.findAll().thenAccept(all -> {
            for (Minion minion : all) {
                minions.put(minion.id(), minion);
                byLocation.put(minion.location(), minion.id());
                ownerCount.merge(minion.owner(), 1, Integer::sum);
            }
            Logs.info("已加载 {} 个仆从", all.size());

            this.tickTask = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, ignored -> tick(), 20L, config.get().tickPeriod());
            Logs.info("全局调度器已启动（周期 {} tick，已加载 {} 个仆从）", config.get().tickPeriod(), all.size());

            async.scheduleAtFixedRate(this::snapshotAndFlush, 5, 5, TimeUnit.SECONDS);

            // 自动售卖轮询改为 GlobalRegionScheduler（region 绑定操作不能在异步线程访问 Inventory）
            long sellIntervalTicks = Math.max(4L, config.get().economy().sellIntervalTicks());
            this.sellTask = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, ignored -> sweepAutoSell(), sellIntervalTicks, sellIntervalTicks);
        }).exceptionally(t -> {
            Logs.error("仆从加载失败", t);
            return null;
        });
    }

    /**
     * 快照落库（线程安全）：对每个脏仆从在其 region 线程生成 {@code MinionData} 快照
     *（Inventory 遍历必须在 region 线程），收集后异步批量落库。
     *
     * <p>修复 P0-2：旧实现直接在异步调度线程调用 {@code minion.toData()}，
     * 此时主线程可能正在操作 GUI 修改 Inventory，导致序列化读到不一致的中间态。</p>
     */
    private void snapshotAndFlush() {
        List<UUID> dirtyIds = repository.collectDirtyIds();
        if (dirtyIds.isEmpty()) {
            return;
        }
        // 不同 region 线程可能并发 add，需线程安全容器
        List<com.hcs.minions.model.MinionData> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (UUID id : dirtyIds) {
            Minion minion = repository.getCached(id);
            if (minion == null) {
                continue;
            }
            BlockLocation loc = minion.location();
            World world = loc.bukkitWorld();
            Location center = loc.toLocation();
            if (world == null || center == null) {
                continue;
            }
            CompletableFuture<Void> task = new CompletableFuture<>();
            tasks.add(task);
            // 在该仆从的 region 线程认领脏标记并生成快照（Inventory 访问安全）
            Bukkit.getRegionScheduler().run(plugin, center, ignored -> {
                try {
                    if (repository.claimDirty(id)) {
                        snapshots.add(minion.toData());
                    }
                } finally {
                    task.complete(null);
                }
            });
        }
        if (!tasks.isEmpty()) {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .whenComplete((v, t) -> {
                        // 单个 region 任务挂起（区块异常等）不应阻塞整批落库：
                        // 超时后先冲刷已收集的快照，未被认领的脏标记下轮重试
                        if (t != null) {
                            Logs.warn("快照收集超时，已冲刷 {} 份已就绪快照，其余下轮重试", snapshots.size());
                        }
                        repository.flushSnapshots(snapshots);
                    });
        }
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (sellTask != null) {
            sellTask.cancel();
        }
        // 先关闭所有打开的仆从 GUI，避免冲刷期间玩家仍在交互（P0-3）
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Minion.StorageHolder) {
                player.closeInventory();
            }
        }
        repository.flushDirtySync();
        entities.despawnAll();
        minions.clear();
        byLocation.clear();
        ownerCount.clear();
    }

    // ------------------------------------------------------------------
    // 全局遍历（关键路径）
    // ------------------------------------------------------------------

    /**
     * 全局遍历：只做「纯内存 + 线程安全的只读判断」（chunk 是否加载、坐标转换），
     * 真正触碰方块/实体/Inventory 的 region 绑定操作全部委派给 {@code RegionScheduler}。
     * 这是 Folia 兼容的关键分界线：GlobalRegionScheduler 内严禁访问 region 状态。
     */
    private void tick() {
        long nowTick = Bukkit.getCurrentTick();
        for (Minion minion : minions.values()) {
            BlockLocation loc = minion.location();
            World world = loc.bukkitWorld();
            if (world == null) {
                continue;
            }
            int cx = loc.x() >> 4;
            int cz = loc.z() >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                world.getChunkAtAsync(cx, cz).exceptionally(t -> {
                    Logs.error("异步区块加载失败: world={}, chunk=({},{})", world.getName(), cx, cz, t);
                    return null;
                });
                continue;
            }
            Location center = loc.toLocation();
            if (center == null) {
                continue;
            }
            // 委派到该仆从所在的 region 线程执行完整工作逻辑
            Bukkit.getRegionScheduler().run(plugin, center, ignored -> processMinion(minion, nowTick));
        }
    }

    /** 在仆从所在 region 线程上执行的完整处理逻辑（Folia 安全）。 */
    private void processMinion(Minion minion, long nowTick) {
        BlockLocation loc = minion.location();
        World world = loc.bukkitWorld();
        if (world == null) {
            return;
        }
        Location center = loc.toLocation();
        if (center == null) {
            return;
        }
        // 玩家活动检查：半径配置化，0 = 永不休眠
        double scanRadius = config.get().playerScanRadius();
        if (scanRadius > 0 && world.getNearbyPlayers(center, scanRadius).isEmpty()) {
            return;
        }

        // 盔甲架按需生成 / 区块卸载后自愈
        if (minion.stand() == null || !minion.stand().isValid()) {
            entities.spawn(minion);
        }

        // 燃料按时间衰减，耗尽后清除加速（无燃料也能工作，燃料只加速）
        if (minion.fuelTicks() > 0) {
            long remaining = Math.max(0, minion.fuelTicks() - config.get().tickPeriod());
            minion.setFuelTicks(remaining);
            if (remaining == 0) {
                minion.setFuelBoost(1.0);
            }
        }

        // GUI 正被观看时每秒刷新状态卡（燃料剩余秒数/下次工作倒计时/仓存等实时跳动）
        if (minion.shouldRefreshGuiView(nowTick)) {
            minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
            minion.markGuiRefreshed(nowTick);
        }

        // 满仓停工（对齐 Hypixel）：仓库已满且无自动售卖手段则停产，头顶展示告警；
        // 玩家取货/开售卖后下轮自动恢复
        boolean halted = minion.isStorageFull() && !shouldAutoSell(minion);
        entities.refreshStatus(minion, halted);
        if (halted) {
            return;
        }

        if (!minion.canWorkNow(nowTick) || !skyblock.canWorkAt(minion)) {
            return;
        }
        performWork(minion, world, loc);
    }

    private void performWork(Minion minion, World world, BlockLocation loc) {
        MinionTypeConfig cfg = config.get().type(minion.type());
        MinionWorkStrategy strategy = strategies.get(minion.type());
        Block anchor = world.getBlockAt(loc.x(), loc.y(), loc.z());

        WorkContext ctx = new WorkContext(minion, world, anchor,
                upgrades.radiusFor(minion, cfg.radiusFor(minion.level())), ThreadLocalRandom.current(), searcher);

        WorkOutcome outcome;
        try {
            if (!strategy.canWork(ctx)) {
                return;
            }
            outcome = strategy.performWork(ctx);
        } catch (Exception e) {
            Logs.error("仆从工作异常: id=" + minion.id() + ", type=" + minion.type(), e);
            return;
        }
        if (!outcome.worked()) {
            if (config.get().debug()) {
                long now = System.currentTimeMillis();
                Long last = lastDebugLog.get(minion.id());
                if (last == null || now - last > 5000) {
                    lastDebugLog.put(minion.id(), now);
                    Logs.info("调试: 仆从 {} 未找到目标 anchor=({},{},{}) radius={}",
                            minion.type().key(), loc.x(), loc.y(), loc.z(), cfg.radiusFor(minion.level()));
                }
            }
            return;
        }

        double efficiency = minion.efficiency(cfg);
        double boost = minion.fuelBoost();
        int cd = Math.max(1, (int) Math.round(cfg.cooldownTicksAt(minion.level()) / (efficiency * boost)));
        minion.scheduleNext(Bukkit.getCurrentTick(), cd);
        entities.swing(minion);

        // 升级模块处理链：自动熔炼 -> 自动压缩 -> 钻石散布（Hypixel 原版玩法）
        List<ItemStack> drops = upgrades.processDrops(minion, outcome.drops(), ThreadLocalRandom.current());
        // 专属稀有掉落（在模块链之后 roll，避免被熔炼/压缩转换）：对齐 Hypixel 各仆从的专属稀有掉落
        if (cfg.hasRareDrop() && ThreadLocalRandom.current().nextDouble() < cfg.rareDropChance()) {
            drops.add(new ItemStack(cfg.rareDrop(), 1));
            Component rare = Messages.rareDrop(cfg.displayName(), MaterialNames.of(cfg.rareDrop()));
            if (config.get().rareDropBroadcast()) {
                Bukkit.getServer().broadcast(rare); // 全服广播（Hypixel 兴奋点，可配为仅通知主人）
            } else {
                Player ownerOnline = Bukkit.getPlayer(minion.owner());
                if (ownerOnline != null) {
                    ownerOnline.sendMessage(rare);
                }
            }
            if (config.get().debug()) {
                Logs.info("调试: 仆从 {} 触发稀有掉落 {}", minion.type().key(), cfg.rareDrop());
            }
        }
        if (config.get().debug() && !drops.isEmpty()) {
            Logs.info("调试: 仆从 {} 工作成功，掉落 {} 种物品", minion.type().key(), drops.size());
        }
        if (!drops.isEmpty()) {
            long produced = drops.stream().mapToLong(ItemStack::getAmount).sum();
            minion.addProduced(produced);
            // Collection 累计（Hypixel 经济底座）
            for (ItemStack item : drops) {
                collection.record(minion.owner(), item.getType(), item.getAmount());
            }
            Map<Integer, ItemStack> leftover = minion.addToStorage(drops.toArray(new ItemStack[0]));
            for (ItemStack item : leftover.values()) {
                world.dropItemNaturally(loc.toLocation().add(0.5, 1.0, 0.5), item);
            }
        }

        if (shouldAutoSell(minion) && minion.isStorageFull()) {
            sell.sellAll(minion);
        }
        minion.markDirty();
    }

    /** 自动售卖触发条件：显式开启 autoSell 开关，或装备了「自动售卖漏斗」模块（对齐 Hypixel Hopper）。 */
    private boolean shouldAutoSell(Minion minion) {
        return minion.autoSell() || upgrades.hasAutoSell(minion);
    }

    /**
     * 自动售卖轮询：在 GlobalRegionScheduler 上遍历，逐个委派到 region 线程判断并售卖。
     * 避免在异步/全局线程直接访问 Bukkit Inventory（非线程安全）。
     */
    private void sweepAutoSell() {
        for (Minion minion : minions.values()) {
            BlockLocation loc = minion.location();
            World world = loc.bukkitWorld();
            if (world == null || !world.isChunkLoaded(loc.x() >> 4, loc.z() >> 4)) {
                continue;
            }
            Location center = loc.toLocation();
            if (center == null) {
                continue;
            }
            Bukkit.getRegionScheduler().run(plugin, center, ignored -> {
                if (shouldAutoSell(minion) && minion.isStorageFull()) {
                    sell.sellAll(minion);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 放置 / 移除 / 打开仓库
    // ------------------------------------------------------------------

    public boolean place(Minion minion, Player player) {
        // 上限 = 权限上限 + Collection 里程碑槽位加成（对齐 Hypixel 里程碑解锁仆从位玩法）
        int cap = permissions.maxMinions(player) + collection.bonusSlots(minion.owner());
        if (countByOwner(minion.owner()) >= cap) {
            return false;
        }
        // 最小间距：防止两个仆从工作区重叠（监听器预检查给出专属提示，此处兜底）
        if (tooCloseToOtherMinion(minion.location())) {
            return false;
        }
        // 坐标占位校验：同一格禁止重复放置（监听器预检查给出专属提示，此处 putIfAbsent 兜底并发竞态）
        if (byLocation.putIfAbsent(minion.location(), minion.id()) != null) {
            return false;
        }
        minions.put(minion.id(), minion);
        ownerCount.merge(minion.owner(), 1, Integer::sum);
        entities.spawn(minion);
        repository.save(minion);
        MinionTypeConfig cfg = config.get().type(minion.type());
        Logs.info("放置仆从 type={} level={} loc=({},{},{}) radius={} fuel={}",
                minion.type().key(), minion.level(),
                minion.location().x(), minion.location().y(), minion.location().z(),
                cfg.radiusFor(minion.level()), minion.fuelTicks());
        Bukkit.getPluginManager().callEvent(new MinionPlacedEvent(minion, player));
        return true;
    }

    /** 按坐标反查仆从（放置前占位校验用）。 */
    public Minion minionAt(BlockLocation location) {
        UUID id = byLocation.get(location);
        return id == null ? null : minions.get(id);
    }

    /** 最小间距校验：与同世界其他仆从的水平切比雪夫距离需 ≥ min-placement-distance（0 = 不限制）。 */
    public boolean tooCloseToOtherMinion(BlockLocation location) {
        int minDistance = config.get().minPlacementDistance();
        if (minDistance <= 0) {
            return false;
        }
        for (Minion other : minions.values()) {
            BlockLocation loc = other.location();
            if (!loc.world().equals(location.world())) {
                continue;
            }
            int dx = Math.abs(loc.x() - location.x());
            if (dx >= minDistance) {
                continue;
            }
            if (Math.max(dx, Math.abs(loc.z() - location.z())) < minDistance) {
                return true;
            }
        }
        return false;
    }

    public Minion remove(Minion minion, Player player) {
        minions.remove(minion.id());
        byLocation.remove(minion.location());
        ownerCount.computeIfPresent(minion.owner(), (k, v) -> v <= 1 ? null : v - 1);
        entities.despawn(minion);
        repository.delete(minion.id());
        Bukkit.getPluginManager().callEvent(new MinionRemovedEvent(minion, player));
        return minion;
    }

    /** 右键小人打开仆从 GUI（仓库内联 + 燃料/升级/收集/拾取）。 */
    public void openGui(Player player, Minion minion) {
        minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
        player.openInventory(minion.storage());
    }

    public void save(Minion minion) {
        minion.markDirty();
        repository.save(minion);
    }

    /**
     * 清理孤儿实体：遍历所有世界，移除 PDC 标记了仆从 UUID 但内存中已无对应仆从数据的盔甲架。
     * 用于异常关闭后残留实体的运维兜底（正常实体 setPersistent(false)，重载后按数据自愈）。
     */
    public int purgeOrphans() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                UUID id = entities.minionIdOf(stand);
                if (id != null && !minions.containsKey(id)) {
                    stand.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Minion minion(UUID id) {
        return minions.get(id);
    }

    public long countByOwner(UUID owner) {
        return ownerCount.getOrDefault(owner, 0);
    }

    public java.util.Collection<Minion> all() {
        return minions.values();
    }
}
