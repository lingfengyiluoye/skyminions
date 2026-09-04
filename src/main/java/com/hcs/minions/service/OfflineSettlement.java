package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.OfflineProductionConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.PlayerTasks;
import com.hcs.minions.work.MinionWorkStrategy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 离线收益结算（对齐 Hypixel 三道平衡锁，见 docs/Hypixel玩法与排版提案.md）：
 * <ul>
 *   <li>① 仓储即天花板：产出按仓库空闲格位截断，满仓=零补发；</li>
 *   <li>② 离线只吃基础速度：不吃燃料加速/倍率/布局加成；</li>
 *   <li>③ 燃料真实燃烧：离线时长从 fuelTicks 中等额扣除。</li>
 * </ul>
 * 时间语义：「区块未加载 / 半径无人 / 主人离线」统一为闲置；
 * 仅主人本人上线时结算自己的仆从。结算第一步原子推进 lastActive，
 * 崩溃最多少发、绝不重复支付。
 */
public final class OfflineSettlement implements Listener {

    private final JavaPlugin plugin;
    private final ConfigProvider config;
    private final MinionManager manager;
    /** 行为 -> 策略 查询函数（由组合根以 WorkStrategyRegistry::get 注入）。 */
    private final java.util.function.Function<com.hcs.minions.model.MinionBehavior, MinionWorkStrategy> strategies;
    private final CollectionService collection;
    /** 模块结算（仓储级压缩）：离线补发同样享受超级压缩/自动压缩。 */
    private final UpgradeService upgrades;

    public OfflineSettlement(JavaPlugin plugin, ConfigProvider config, MinionManager manager,
                             java.util.function.Function<com.hcs.minions.model.MinionBehavior, MinionWorkStrategy> strategies,
                             CollectionService collection, UpgradeService upgrades) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.strategies = strategies;
        this.collection = collection;
        this.upgrades = upgrades;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var owned = manager.all().stream()
                .filter(m -> player.getUniqueId().equals(m.owner()))
                .toList();
        if (owned.isEmpty()) {
            return;
        }
        // 延迟数秒再结算：等玩家完全进入世界，消息与区块更稳
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> settleAll(player.getUniqueId(), owned), 60L);
    }

    private void settleAll(java.util.UUID playerId, List<Minion> minions) {
        Player online = Bukkit.getPlayer(playerId);
        if (online == null) {
            return; // 闪断：放弃本轮，闲置窗留给下次
        }
        for (Minion minion : minions) {
            Location center = minion.location().toLocation();
            if (center == null || center.getWorld() == null) {
                continue;
            }
            Bukkit.getRegionScheduler().run(plugin, center, t -> settleOne(minion));
        }
    }

    private void settleOne(Minion minion) {
        try {
            OfflineProductionConfig cfgOff = config.get().offlineProduction();
            if (!cfgOff.enabled()) {
                return;
            }
            long now = System.currentTimeMillis();
            long idleSec = (now - minion.lastActiveEpochMs()) / 1000L;
            if (idleSec < cfgOff.minSeconds()) {
                return;
            }
            long cappedSec = Math.min(idleSec, cfgOff.maxHours() * 3600L);

            MinionTypeConfig cfg = config.get().type(minion.type());
            if (cfg == null) {
                Logs.warn("离线结算跳过缺失配置的仆从类型: {}", minion.type().key());
                return;
            }

            // 仓储级压缩（超级压缩 3000 / 自动压缩）：先压缩腾格再算空位天花板，
            // 与在线 processMinion 的顺序一致，避免「满仓散装 → 零补发 → 永不压缩」死锁
            upgrades.compactStorage(minion);

            // 锁①：仓储空位天花板（先判断——满仓则既不烧燃料也不产出，
            // 与在线"满仓停工不衰减燃料"口径一致，避免满仓挂机白白损耗燃料）
            long freeUnits = freeUnitsOf(minion, cfg.product());
            if (freeUnits <= 0) {
                sendSummary(minion, cfg, 0, List.of());
                minion.setLastActiveEpochMs(now);
                return;
            }

            // 锁③：燃料真实燃烧（先于产出计算）
            long burnTicks = Math.min(minion.fuelTicks(), cappedSec * 20L);
            if (burnTicks > 0) {
                minion.setFuelTicks(minion.fuelTicks() - burnTicks);
            }

            // 锁②：基础速度 = 等级冷却曲线 ÷ 等级效率；无燃料/倍率/布局加成
            double eff = cfg.efficiencyAt(minion.level());
            double cdTicks = cfg.cooldownTicksAt(minion.level()) / Math.max(0.01, eff);
            long actionsL = (long) Math.floor(cappedSec * 20.0 / Math.max(1.0, cdTicks));
            actionsL = actionsL * Math.max(0, cfgOff.ratePercent()) / 100;
            int actions = (int) Math.min(actionsL, 5_000_000);
            if (actions <= 0) {
                // 配置为 0% 或闲置时间不足以完成一次动作时，时间窗口仍已结算，避免下次上线重复扣燃料。
                minion.setLastActiveEpochMs(now);
                return;
            }

            MinionWorkStrategy strategy = strategies.apply(minion.type().behavior());
            List<ItemStack> yields = strategy.offlineYield(cfg, actions, ThreadLocalRandom.current(), freeUnits);
            if (yields.isEmpty()) {
                // 燃料已在上方按闲置时长扣除；即便本轮无产出，也必须推进已支付指针，
                // 否则下次上线会用同一段旧 lastActive 重算窗口、重复扣燃料。
                minion.setLastActiveEpochMs(now);
                return;
            }

            long totalUnits = yields.stream().mapToLong(ItemStack::getAmount).sum();
            java.util.Map<Integer, ItemStack> leftovers = minion.addToStorage(yields.toArray(new ItemStack[0]));
            // 离线结算也不能静默吞掉仓库无法容纳的物品；当前先安全掉落，后续可替换为邮件箱。
            if (!leftovers.isEmpty()) {
                Location dropAt = minion.location().toLocation();
                if (dropAt != null && dropAt.getWorld() != null) {
                    for (ItemStack leftover : leftovers.values()) {
                        dropAt.getWorld().dropItemNaturally(dropAt.clone().add(0.5, 1.0, 0.5), leftover);
                    }
                }
            }
            // 只有物品已经入仓/安全交付后才推进已支付指针，避免异常导致时间窗口被吞掉。
            minion.setLastActiveEpochMs(now);
            // 补发的散装立即过一轮仓储级压缩（装了压缩模块的玩家上线即见附魔资源）
            upgrades.compactStorage(minion);
            for (ItemStack item : yields) {
                collection.record(minion.owner(), item.getType(), item.getAmount());
            }
            long storedUnits = totalUnits - leftovers.values().stream().mapToLong(ItemStack::getAmount).sum();
            sendSummary(minion, cfg, Math.max(0, storedUnits), yields);
            PlayerTasks.run(plugin, minion.owner(), ownerOnlineNow ->
                    com.hcs.minions.util.Fx.sound(ownerOnlineNow,
                            org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f));
            Logs.info("离线结算: 仆从 {} 闲置 {}s 补发 {} 件", minion.id(), cappedSec, totalUnits);
        } catch (Exception e) {
            Logs.error("离线结算失败（跳过该仆从，不影响其他）: id=" + minion.id(), e);
        }
    }

    /** 仓库空位（单位数）：空格按产物最大堆叠计 + 同产物未满堆的剩余空间。 */
    private static long freeUnitsOf(Minion minion, org.bukkit.Material product) {
        long free = 0;
        int maxSize = Math.max(1, product.getMaxStackSize());
        for (int i = 0; i < minion.unlockedSlots(); i++) {
            ItemStack item = minion.storage().getItem(
                    com.hcs.minions.model.Minion.storageSlots()[i]);
            if (item == null || item.getType().isAir()) {
                free += maxSize;
            } else if (item.getType() == product) {
                free += Math.max(0, item.getMaxStackSize() - item.getAmount());
            }
        }
        return free;
    }

    private void sendSummary(Minion minion, MinionTypeConfig cfg, long totalUnits, List<ItemStack> yields) {
        if (totalUnits <= 0) {
            return;
        }
        PlayerTasks.run(plugin, minion.owner(), owner -> {
            owner.sendMessage(com.hcs.minions.util.Messages.offlineHeader(cfg.displayName()));
            yields.stream()
                    .sorted((a, b) -> Integer.compare(b.getAmount(), a.getAmount()))
                    .limit(3)
                    .forEach(item -> owner.sendMessage(com.hcs.minions.util.Messages.offlineDetail(
                            com.hcs.minions.util.MaterialNames.of(item.getType()), item.getAmount())));
            owner.sendMessage(com.hcs.minions.util.Messages.offlineTotal(totalUnits));
        });
    }
}
