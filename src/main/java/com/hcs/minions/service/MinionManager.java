package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.event.MinionPlacedEvent;
import com.hcs.minions.event.MinionRemovedEvent;
import com.hcs.minions.model.BlockLocation;
import com.hcs.minions.model.MinionStatus;
import com.hcs.minions.model.Minion;
import com.hcs.minions.repository.MinionRepository;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Messages;
import com.hcs.minions.util.PlayerTasks;
import com.hcs.minions.util.Sounds;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /**
     * 放置守卫：数量上限 / 同格禁放 / 最小间距 的原子占位（TOCTOU 由它关闭，
     * 并发不变量见 PlacementGuardTest）。
     */
    private final PlacementGuard placement = new PlacementGuard();
    /** 仆从坐标空间索引：世界+区块 -> 该桶内的坐标（放置间距检查用，O(局部密度) 而非 O(全部仆从)）。 */
    private final ConcurrentHashMap<ChunkKey, List<BlockLocation>> locationIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastDebugLog = new ConcurrentHashMap<>();
    /** 处于「休眠」（半径无人）状态的仆从 id：恢复运转时补发闲置窗产出。 */
    private final Set<UUID> dormantIds = ConcurrentHashMap.newKeySet();
    /**
     * 仆从从休眠恢复运转时的回调（离线结算入口，由组合根在装配期注入）。
     * 用 volatile 回调而非直接持有 OfflineSettlement：两者的构造有先后依赖。
     */
    private volatile java.util.function.Consumer<Minion> onReactivate;
    /** 仆从诊断（只读）：由组合根在装配期注入（需要 strategies/skyblock/upgrades/searcher）。 */
    private MinionDiagnostics diagnostics;
    /** 在途快照批次（提升为字段以便 stop() 强制结算；无批次时为 null）。 */
    private volatile SnapshotBatcher inFlightBatch;

    private ScheduledTask tickTask;
    private ScheduledTask sellTask;
    private ScheduledFuture<?> snapshotTask;

    // ---- 运行时统计（/minion stats）：原子计数，调度线程单写、任意线程读 ----
    private volatile long startMillis;
    private volatile boolean ready;
    private final java.util.concurrent.atomic.AtomicLong tickCycles = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rareDropRolls = new java.util.concurrent.atomic.AtomicLong();

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
        ready = false;
        repository.findAllData().thenAccept(rows -> {
            if (rows.isEmpty()) {
                Logs.info("未加载到仆从数据");
            }
            // Minion 装配涉及 Bukkit Inventory 创建/写入，必须在全局线程执行，
            // 不得在异步虚拟线程上构造（Bukkit API 非线程安全）
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> assembleAndStart(rows));
        }).exceptionally(t -> {
            Logs.error("仆从加载失败", t);
            return null;
        });
    }

    /** 在全局线程装配仆从对象并启动调度器（由 findAllData 回调跳转至此）。 */
    private void assembleAndStart(List<com.hcs.minions.model.MinionData> rows) {
        for (com.hcs.minions.model.MinionData data : rows) {
            Minion minion;
            try {
                minion = Minion.fromData(data);
                MinionTypeConfig typeConfig = config.get().type(minion.type());
                if (typeConfig != null) {
                    minion.refresh(typeConfig, config.get().upgradeRequirePreviousBody());
                }
            } catch (Exception e) {
                Logs.error("仆从数据装配失败，已跳过: id=" + data.id(), e);
                continue;
            }
            minions.put(minion.id(), minion);
            // 装配期直接占位（不走 cap 检查：这些仆从是既有数据，不是新放置）
            placement.admit(minion.owner(), minion.id(), minion.location());
            indexLocation(minion);
            repository.register(minion);
        }
        Logs.info("已加载 {} 个仆从", minions.size());
        this.startMillis = System.currentTimeMillis();
        this.ready = true;

        this.tickTask = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, ignored -> tick(), 20L, config.get().tickPeriod());
        Logs.info("全局调度器已启动（周期 {} tick，已加载 {} 个仆从）", config.get().tickPeriod(), minions.size());

        snapshotTask = async.scheduleAtFixedRate(this::snapshotAndFlush, 5, 5, TimeUnit.SECONDS);

        // 自动售卖轮询改为 GlobalRegionScheduler（region 绑定操作不能在异步线程访问 Inventory）
        long sellIntervalTicks = Math.max(4L, config.get().economy().sellIntervalTicks());
        this.sellTask = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, ignored -> sweepAutoSell(), sellIntervalTicks, sellIntervalTicks);
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
        // 不同 region 线程可能并发完成；超时后迟到的快照必须单独补刷，不能写入已提交批次。
        // 这条「迟到快照归属」规则由 SnapshotBatcher 承载（并发不变量有回归测试）。
        // 提升为字段：stop() 才能在 allOf 永不完成（region 任务挂起）时强制结算，
        // 否则已认领脏标记的快照会 stranded 在批次里无人落库
        SnapshotBatcher batch = new SnapshotBatcher();
        this.inFlightBatch = batch;
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
                com.hcs.minions.model.MinionData snapshot = null;
                try {
                    if (repository.claimDirty(id)) {
                        snapshot = minion.toData();
                    }
                } finally {
                    if (snapshot != null && !batch.offer(snapshot)) {
                        // 批次已结算（超时）：这份迟到的快照必须单独补刷，绝不能并进已落库的批次
                        repository.flushSnapshots(List.of(snapshot));
                    }
                    task.complete(null);
                }
            });
        }
        if (!tasks.isEmpty()) {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .whenComplete((v, t) -> {
                        List<com.hcs.minions.model.MinionData> ready = batch.settle();
                        // 单个 region 任务挂起（区块异常等）不应阻塞整批落库：
                        // 超时后先冲刷已收集的快照；迟到快照由 region 回调单独补刷。
                        if (t != null) {
                            Logs.warn("快照收集超时，已冲刷 {} 份已就绪快照，其余迟到任务单独补刷", ready.size());
                        }
                        repository.flushSnapshots(ready);
                    });
        }
    }

    public void stop() {
        ready = false;
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (sellTask != null) {
            sellTask.cancel();
        }
        if (snapshotTask != null) {
            snapshotTask.cancel(false);
        }
        // 先关闭所有打开的仆从 GUI，避免冲刷期间玩家仍在交互（P0-3）
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Minion.StorageHolder) {
                player.closeInventory();
            }
        }
        // 在途快照批次强制结算：若某个 region 任务挂起导致 allOf 永不完成，
        // 已 claimDirty（脏标记已置 false）的快照会 stranded 在批次里，
        // 而 flushDirtySync 只扫 dirty 集合捞不回来 —— 这里补上最后一道
        if (inFlightBatch != null) {
            List<com.hcs.minions.model.MinionData> stranded = inFlightBatch.settle();
            if (!stranded.isEmpty()) {
                Logs.warn("关闭时强制结算在途快照批次，补刷 {} 份", stranded.size());
                repository.flushSnapshots(stranded);
            }
            inFlightBatch = null;
        }
        repository.flushDirtySync();
        entities.despawnAll();
        minions.clear();
        locationIndex.clear(); // 空间索引同步清空
        placement.clear();
    }

    // ------------------------------------------------------------------
    // 全局遍历（关键路径）
    // ------------------------------------------------------------------

    /**
     * 全局遍历：只做「纯内存 + 线程安全的只读判断」（chunk 是否加载、坐标转换），
     * 真正触碰方块/实体/Inventory 的 region 绑定操作全部委派给 {@code RegionScheduler}。
     * 这是 Folia 兼容的关键分界线：GlobalRegionScheduler 内严禁访问 region 状态。
     *
     * <p><b>按 region 合批</b>：同一 region（世界 + 32×32 区块网格）的仆从合并为
     * <b>一个</b> region 任务，任务数从 O(仆从数) 降到 O(region 数)。1000 个仆从挤在
     * 几张图上时，调度提交从 1000 次降到十几次，且不引入任何额外延迟
     * （对比「每 tick 只处理 50 个」的分片轮询——那会给末尾仆从增加 N/50 tick 延迟）。</p>
     */
    private void tick() {
        long nowTick = Bukkit.getCurrentTick();
        tickCycles.incrementAndGet();
        // region key -> 该 region 本轮待处理的仆从
        Map<RegionKey, List<Minion>> byRegion = new HashMap<>();
        for (Minion minion : minions.values()) {
            BlockLocation loc = minion.location();
            World world = loc.bukkitWorld();
            if (world == null) {
                continue;
            }
            int cx = loc.x() >> 4;
            int cz = loc.z() >> 4;
            // 区块未加载 = 附近无玩家活动：跳过且绝不主动加载（getChunkAtAsync 会把
            // 所有仆从区块变成常加载，既浪费内存也使休眠机制失效）
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            Location center = loc.toLocation();
            if (center == null) {
                continue;
            }
            byRegion.computeIfAbsent(new RegionKey(world, cx >> 5, cz >> 5), k -> new ArrayList<>())
                    .add(minion);
        }
        // 每个 region 一个任务：任务内在同一 region 线程串行处理该组仆从
        for (Map.Entry<RegionKey, List<Minion>> e : byRegion.entrySet()) {
            List<Minion> group = e.getValue();
            if (group.isEmpty()) {
                continue;
            }
            Location dispatchAt = group.get(0).location().toLocation();
            if (dispatchAt == null) {
                continue;
            }
            Bukkit.getRegionScheduler().run(plugin, dispatchAt, ignored -> {
                for (Minion minion : group) {
                    processMinion(minion, nowTick);
                }
            });
        }
    }

    /** region 归属键：世界 + 32×32 区块网格坐标（Folia region 划分单位）。 */
    private record RegionKey(World world, int rx, int rz) {
    }

    /** 在仆从所在 region 线程上执行的完整处理逻辑（Folia 安全）。 */
    private void processMinion(Minion minion, long nowTick) {
        BlockLocation loc = minion.location();
        World world = loc.bukkitWorld();
        if (world == null) {
            minion.setStatus(MinionStatus.CHUNK_UNLOADED);
            return;
        }
        Location center = loc.toLocation();
        if (center == null) {
            minion.setStatus(MinionStatus.CHUNK_UNLOADED);
            return;
        }
        MinionTypeConfig typeConfig = config.get().type(minion.type());
        if (typeConfig == null) {
            minion.setStatus(MinionStatus.CONFIG_MISSING);
            entities.refreshStatus(minion, MinionEntityService.PlateStatus.HALTED);
            return;
        }

        // 盔甲架按需生成 / 区块卸载后自愈（提前到玩家检查之前：
        // 闲置仆从也需要名牌展示 ⏾ 状态）
        if (minion.stand() == null || !minion.stand().isValid()) {
            entities.spawn(minion);
        }

        // 玩家活动检查：半径配置化，0 = 永不休眠
        double scanRadius = config.get().playerScanRadius();
        boolean playersNearby = scanRadius <= 0 || !world.getNearbyPlayers(center, scanRadius).isEmpty();
        if (!playersNearby) {
            // 闲置（统一离线语义）：不实时工作，产出由主人上线/恢复运转时一次性结算；
            // 名牌显示 ⏾ 闲置挂机中
            minion.setStatus(MinionStatus.DORMANT);
            entities.refreshStatus(minion, MinionEntityService.PlateStatus.DORMANT);
            dormantIds.add(minion.id());
            return;
        }
        // 休眠 → 运转 转换：补发这段闲置窗的产出（幂等，lastActive 指针保证不重复支付）。
        // 覆盖「主人在线但走远」的盲区——join 结算路径只处理真正下线的时间
        if (dormantIds.remove(minion.id())) {
            java.util.function.Consumer<Minion> callback = onReactivate;
            if (callback != null) {
                callback.accept(minion);
            }
        }
        // 在线处理期间持续推进 lastActive（30s 节流）：
        // 这是离线结算的"已支付指针"，必须与实时产出保持同步，否则会重复支付
        long now = System.currentTimeMillis();
        if (now - minion.lastActiveEpochMs() > 30_000L) {
            minion.setLastActiveEpochMs(now);
        }

        // GUI 正被观看时每秒刷新状态卡（燃料剩余秒数/下次工作倒计时/仓存等实时跳动）
        if (minion.shouldRefreshGuiView(nowTick)) {
            minion.refresh(typeConfig, config.get().upgradeRequirePreviousBody());
            minion.markGuiRefreshed(nowTick);
        }

        // 仓储级压缩结算（自动压缩/超级压缩 3000）：必须在停工判定之前，
        // 否则「散装塞满仓 → 停工 → 永不压缩」死锁；压缩腾出格位后自然复产。
        // 已在 region 线程上，consume/addToStorage 线程安全
        upgrades.compactStorage(minion);

        // 满仓停工（对齐 Hypixel）：仓库已满且无自动售卖手段则停产，头顶展示告警；
        // 玩家取货/开售卖后下轮自动恢复。停工期间不衰减燃料/倍率——与"无人休眠"口径一致，
        // 避免产出为零时仍白白烧掉玩家的燃料和催化剂时长（离线结算路径本就不烧空转燃料）。
        boolean halted = minion.isStorageFull() && !hasSellOutlet(minion);
        entities.refreshStatus(minion, halted
                ? MinionEntityService.PlateStatus.HALTED
                : MinionEntityService.PlateStatus.WORKING);
        if (!halted) {
            minion.setStatus(MinionStatus.WORKING);
        }
        if (halted) {
            minion.setStatus(MinionStatus.HALTED_FULL);
            return;
        }

        // 产量倍率时长仅在实际运转（非休眠、非停工）时衰减
        minion.tickMultiplier(config.get().tickPeriod());

        // 燃料按时间衰减，耗尽后清除加速（无燃料也能工作，燃料只加速）
        if (minion.fuelTicks() > 0) {
            long remaining = Math.max(0, minion.fuelTicks() - config.get().tickPeriod());
            minion.setFuelTicks(remaining);
            if (remaining == 0) {
                minion.setFuelBoost(1.0);
                // 燃料耗尽要有提示音：否则玩家只会觉得"莫名变慢了"
                PlayerTasks.run(plugin, minion.owner(), Sounds::fuelOut);
            }
        }

        if (!minion.canWorkNow(nowTick) || !skyblock.canWorkAt(minion)) {
            minion.setStatus(skyblock.canWorkAt(minion) ? MinionStatus.COOLDOWN : MinionStatus.BLOCKED_BY_ISLAND);
            return;
        }
        performWork(minion, world, loc);
    }

    private void performWork(Minion minion, World world, BlockLocation loc) {
        MinionTypeConfig cfg = config.get().type(minion.type());
        if (cfg == null) {
            return;
        }
        MinionWorkStrategy strategy = strategies.get(minion.type().behavior());
        Block anchor = world.getBlockAt(loc.x(), loc.y(), loc.z());

        WorkContext ctx = new WorkContext(minion, world, anchor, cfg,
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

        // 升级模块掉落链：自动熔炼 -> 钻石散布 -> 腐化之土（压缩类模块走仓储级结算，见 processMinion）
        List<ItemStack> drops = upgrades.processDrops(minion, outcome.drops(), ThreadLocalRandom.current());
        // 专属稀有掉落（在模块链之后 roll，避免被熔炼/压缩转换）：对齐 Hypixel 各仆从的专属稀有掉落
        if (cfg.hasRareDrop() && ThreadLocalRandom.current().nextDouble() < cfg.rareDropChance()) {
            drops.add(new ItemStack(cfg.rareDrop(), 1));
            rareDropRolls.incrementAndGet();
            Component rare = Messages.rareDrop(cfg.displayName(), MaterialNames.of(cfg.rareDrop()));
            if (config.get().rareDropBroadcast()) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, () ->
                        Bukkit.getServer().broadcast(rare));
            } else {
                PlayerTasks.run(plugin, minion.owner(), player -> player.sendMessage(rare));
            }
            // 主人在线：Title 高光 + 挑战完成音（Hypixel 稀有时刻仪式感）；
            // 文案走 gui.yml 模板（rare-drop.*），不再硬编码
            PlayerTasks.run(plugin, minion.owner(), ownerForFx -> {
                com.hcs.minions.util.Fx.title(ownerForFx,
                        GuiText.title("rare-drop.title"),
                        GuiText.title("rare-drop.subtitle",
                                Map.of("name", MaterialNames.of(cfg.rareDrop()))));
                com.hcs.minions.util.Fx.sound(ownerForFx, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
            });
            if (config.get().debug()) {
                Logs.info("调试: 仆从 {} 触发稀有掉落 {}", minion.type().key(), cfg.rareDrop());
            }
        }
        if (config.get().debug() && !drops.isEmpty()) {
            Logs.info("调试: 仆从 {} 工作成功，掉落 {} 种物品", minion.type().key(), drops.size());
        }
        // 产量倍率燃料（催化剂轴）：数量放大，速度不变
        double mult = minion.prodMultiplier();
        if (mult > 1.0 && !drops.isEmpty()) {
            for (int i = 0; i < drops.size(); i++) {
                ItemStack d = drops.get(i);
                int scaled = (int) Math.min(Integer.MAX_VALUE / 2,
                        Math.round(d.getAmount() * mult));
                d.setAmount(Math.max(1, scaled));
            }
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

        // 即时售卖漏斗（Hypixel Budget/Enchanted Hopper）：每次产出后按折价立即卖，无需等满仓。
        // 与"满仓全价卖"（autoSell/自动售卖漏斗）互斥优先级：满仓全价路径在下方；
        // 装了即时漏斗则本轮已折价清仓，通常不会再触发满仓。
        double hopperRatio = upgrades.instantSellRatio(minion);
        if (hopperRatio > 0.0) {
            sell.sellAll(minion, hopperRatio);
        } else if (shouldAutoSell(minion) && minion.isStorageFull()) {
            sell.sellAll(minion);
        }
        // 工作音：只播给正在看这个仆从 GUI 的玩家——满岛几十个仆从同时收割时
        // 不能变成噪音，但盯着界面时那声轻响是"仆从活着"的关键反馈
        if (!minion.storage().getViewers().isEmpty()) {
            for (var viewer : minion.storage().getViewers()) {
                if (viewer instanceof Player p) {
                    Sounds.harvest(p);
                }
            }
        }
        minion.markDirty();
    }

    /** 自动售卖触发条件：显式开启 autoSell 开关，或装备了「自动售卖漏斗」模块（对齐 Hypixel Hopper）。 */
    private boolean shouldAutoSell(Minion minion) {
        return minion.autoSell() || upgrades.hasAutoSell(minion);
    }

    /** 是否具备任一"满仓不停工"手段：满仓全价卖 或 即时折价漏斗（对齐 Hypixel：装漏斗永不停工）。 */
    private boolean hasSellOutlet(Minion minion) {
        return shouldAutoSell(minion) || upgrades.hasInstantHopper(minion);
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
        if (!ready) {
            return false;
        }
        // 上限 = 权限上限 + Collection 里程碑槽位加成（对齐 Hypixel 里程碑解锁仆从位玩法）。
        // 三项检查（数量/同格/间距）由 PlacementGuard 在一把锁内原子完成，
        // 任一失败完整回滚——关闭 Folia 多 region 并发放置的 TOCTOU 超限窗口。
        // 该守卫的并发不变量由 PlacementGuardTest 守住。
        int cap = permissions.maxMinions(player) + collection.bonusSlots(minion.owner());
        int minDistance = config.get().minPlacementDistance();
        boolean reserved = placement.tryReserve(
                minion.owner(), minion.id(), cap,
                minion.location(), minDistance,
                // 关闭间距限制时不构建坐标列表（放置是低频操作，O(n) 也可接受）
                minDistance > 0 ? existingLocations(minion.location()) : List.of());
        if (!reserved) {
            return false;
        }
        minions.put(minion.id(), minion);
        indexLocation(minion); // 空间索引登记（间距检查用）
        MinionTypeConfig cfg = config.get().type(minion.type());
        entities.spawn(minion);
        repository.save(minion);
        if (cfg == null) {
            cfg = config.get().type(minion.type());
        }
        Logs.info("放置仆从 type={} level={} loc=({},{},{}) radius={} fuel={}",
                minion.type().key(), minion.level(),
                minion.location().x(), minion.location().y(), minion.location().z(),
                cfg == null ? 0 : cfg.radiusFor(minion.level()), minion.fuelTicks());
        Sounds.place(player); // 放置是满足感最强的一刻，必须有声音确认
        Bukkit.getPluginManager().callEvent(new MinionPlacedEvent(minion, player));
        return true;
    }

    /**
     * 现有仆从坐标（间距检查用）。
     *
     * <p><b>空间索引</b>：按「世界 + 16×16 区块」分桶，只返回候选点所在桶及其
     * 8 个邻居桶的仆从。原来是 O(全部仆从) 建表——500 个仆从后每次放置都扫 500 遍；
     * 现在代价与仆从总数无关，只与局部密度相关。间距阈值通常很小（默认 1），
     * 单桶足以覆盖，取 3×3 桶是为大间距值留余量。</p>
     */
    private Iterable<BlockLocation> existingLocations(BlockLocation around) {
        List<BlockLocation> out = new ArrayList<>();
        String world = around.world();
        int bx = around.x() >> 4;
        int bz = around.z() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<BlockLocation> bucket = locationIndex.get(chunkKey(world, bx + dx, bz + dz));
                if (bucket != null) {
                    out.addAll(bucket);
                }
            }
        }
        return out;
    }

    /** 分桶键：世界 + 区块坐标（用 record 保证 equals/hashCode 正确）。 */
    private record ChunkKey(String world, int cx, int cz) {
    }

    private static ChunkKey chunkKey(String world, int cx, int cz) {
        return new ChunkKey(world, cx, cz);
    }

    /** 把仆从坐标登记进空间索引（admit/place 成功后调用）。 */
    private void indexLocation(Minion minion) {
        BlockLocation loc = minion.location();
        locationIndex.computeIfAbsent(chunkKey(loc.world(), loc.x() >> 4, loc.z() >> 4),
                k -> new ArrayList<>()).add(loc);
    }

    /** 从空间索引移除（remove 时调用；O(桶长) 而非 O(全部)。 */
    private void unindexLocation(Minion minion) {
        BlockLocation loc = minion.location();
        List<BlockLocation> bucket = locationIndex.get(chunkKey(loc.world(), loc.x() >> 4, loc.z() >> 4));
        if (bucket != null) {
            bucket.remove(loc);
            if (bucket.isEmpty()) {
                locationIndex.remove(chunkKey(loc.world(), loc.x() >> 4, loc.z() >> 4), bucket);
            }
        }
    }

    /** 按坐标反查仆从（放置前占位校验用）。 */
    public Minion minionAt(BlockLocation location) {
        UUID id = placement.idAt(location);
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
        // 占位释放由 PlacementGuard 内部锁保证原子（place() 的占位也用同一把锁），
        // 无需外层锁——早先的外层 placementLock 在 place() 改走 guard 后已无人使用
        minions.remove(minion.id());
        placement.release(minion.owner(), minion.location());
        unindexLocation(minion); // 空间索引同步移除
        lastDebugLog.remove(minion.id()); // 防止 debug 节流缓存随时间缓慢泄漏
        dormantIds.remove(minion.id()); // 休眠集合同步清理，避免按 id 泄漏
        sell.forget(minion.id()); // 清理售卖在途闸门，避免 inFlight 随仆从增删泄漏
        entities.despawn(minion);
        repository.delete(minion.id());
        Bukkit.getPluginManager().callEvent(new MinionRemovedEvent(minion, player));
        return minion;
    }

    /** 右键小人打开仆从 GUI（仓库内联 + 燃料/升级/收集/拾取）。 */
    public void openGui(Player player, Minion minion) {
        if (!ready) {
            player.sendMessage(Messages.loading());
            return;
        }
        MinionTypeConfig typeConfig = config.get().type(minion.type());
        if (typeConfig == null) {
            player.sendMessage(Messages.typeConfigMissing());
            return;
        }
        minion.refresh(typeConfig, config.get().upgradeRequirePreviousBody());
        player.openInventory(minion.storage());
    }

    public void save(Minion minion) {
        minion.markDirty();
        repository.save(minion);
    }

    /**
     * 注入休眠恢复回调（离线结算）。组合根在装配期调用：
     * 两者有构造先后依赖，故用 setter 而非构造参数。
     */
    public void setOnReactivate(java.util.function.Consumer<Minion> callback) {
        this.onReactivate = callback;
    }

    /** 诊断单个仆从（只读；回答「它为什么没在产出」）。 */
    public MinionDiagnostics.Report diagnose(Minion minion) {
        return diagnostics.inspect(minion);
    }

    /** 诊断一批仆从（按严重度排序：异常的排前面）。 */
    public List<MinionDiagnostics.Report> diagnoseAll(List<Minion> minions) {
        return diagnostics.inspectAll(minions);
    }

    /** 注入诊断器（组合根装配期调用：依赖 strategies/skyblock/upgrades/searcher）。 */
    public void setDiagnostics(MinionDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * 清理孤儿实体：遍历所有世界，移除 PDC 标记了仆从 UUID 但内存中已无对应仆从数据的盔甲架。
     * 用于异常关闭后残留实体的运维兜底（正常实体 setPersistent(false)，重载后按数据自愈）。
     *
     * <p>实体移除必须在该实体自己的 region 线程执行（Folia），故先收集再逐个 dispatch；
     * 返回值是「待移除数」，dispatch 为异步完成。</p>
     */
    public int purgeOrphans() {
        List<ArmorStand> orphans = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                UUID id = entities.minionIdOf(stand);
                if (id != null && !minions.containsKey(id)) {
                    orphans.add(stand);
                }
            }
        }
        if (orphans.isEmpty()) {
            return 0;
        }
        // 按 region 合批移除：与 tick() 同一思路——N 个孤儿从 N 个调度任务降到
        // O(region 数) 个。移除仍在该 region 线程执行（Folia 实体线程约束）
        Map<RegionKey, List<ArmorStand>> byRegion = new HashMap<>();
        for (ArmorStand stand : orphans) {
            Location at = stand.getLocation();
            World world = at.getWorld();
            if (world == null) {
                continue;
            }
            byRegion.computeIfAbsent(new RegionKey(world, at.getBlockX() >> 9, at.getBlockZ() >> 9),
                    k -> new ArrayList<>()).add(stand);
        }
        for (Map.Entry<RegionKey, List<ArmorStand>> e : byRegion.entrySet()) {
            List<ArmorStand> group = e.getValue();
            Location dispatchAt = group.get(0).getLocation();
            Bukkit.getRegionScheduler().run(plugin, dispatchAt, task -> {
                for (ArmorStand stand : group) {
                    if (stand.isValid()) {
                        stand.remove();
                    }
                }
            });
        }
        return orphans.size();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Minion minion(UUID id) {
        return minions.get(id);
    }

    public long countByOwner(UUID owner) {
        return placement.countOf(owner);
    }

    public java.util.Collection<Minion> all() {
        return minions.values();
    }

    /**
     * 运行时统计（/minion stats）：运行时长、调度周期数、稀有掉落次数、
     * 放置数与全场累计产出。文案走 messages.yml 模板（stats-* 键），
     * 只读内存快照，任意线程可调。
     */
    public List<Component> statsLines() {
        long upMillis = startMillis <= 0 ? 0 : System.currentTimeMillis() - startMillis;
        long upSeconds = upMillis / 1000;
        long permNodes;
        try {
            permNodes = Bukkit.getPluginManager().getPermissions().stream()
                    .filter(per -> per.getName().startsWith("minions.")).count();
        } catch (Exception e) {
            permNodes = -1; // 无头单测等无 Bukkit 场景
        }
        String uptime = upSeconds >= 3600
                ? (upSeconds / 3600) + " 小时 " + (upSeconds % 3600) / 60 + " 分钟"
                : upSeconds / 60 + " 分钟 " + upSeconds % 60 + " 秒";
        long totalProducedAll = 0;
        int working = 0;
        for (Minion m : minions.values()) {
            totalProducedAll += m.totalProduced();
            // 口径与 processMinion 的停工判断一致：仓库未满或具备售卖出口（全价/即时漏斗）即视为工作中
            if (!m.isStorageFull() || hasSellOutlet(m)) {
                working++;
            }
        }
        List<Component> out = new ArrayList<>();
        out.add(Messages.statsUptime(uptime));
        out.add(Messages.statsCycles(tickCycles.get()));
        if (permNodes >= 0) {
            out.add(Messages.statsPermissions(permNodes));
        }
        out.add(Messages.statsPlaced(minions.size(), working));
        out.add(Messages.statsRareDrops(rareDropRolls.get()));
        out.add(Messages.statsTotalProduced(totalProducedAll));
        return out;
    }
}
