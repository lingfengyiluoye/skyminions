package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.OfflineProductionConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.util.Logs;
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

    public OfflineSettlement(JavaPlugin plugin, ConfigProvider config, MinionManager manager,
                             java.util.function.Function<com.hcs.minions.model.MinionBehavior, MinionWorkStrategy> strategies,
                             CollectionService collection) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.strategies = strategies;
        this.collection = collection;
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

            // 锁③：燃料真实燃烧（先于产出计算）
            long burnTicks = Math.min(minion.fuelTicks(), cappedSec * 20L);
            if (burnTicks > 0) {
                minion.setFuelTicks(minion.fuelTicks() - burnTicks);
            }

            MinionTypeConfig cfg = config.get().type(minion.type());
            if (cfg == null) {
                return;
            }
            // 锁②：基础速度 = 等级冷却曲线 ÷ 等级效率；无燃料/倍率/布局加成
            double eff = cfg.efficiencyAt(minion.level());
            double cdTicks = cfg.cooldownTicksAt(minion.level()) / Math.max(0.01, eff);
            long actionsL = (long) Math.floor(cappedSec * 20.0 / Math.max(1.0, cdTicks));
            actionsL = actionsL * Math.max(0, cfgOff.ratePercent()) / 100;
            int actions = (int) Math.min(actionsL, 5_000_000);
            if (actions <= 0) {
                return;
            }

            // 锁①：仓储空位天花板
            long freeUnits = freeUnitsOf(minion, cfg.product());
            if (freeUnits <= 0) {
                sendSummary(minion, cfg, 0, List.of());
                return;
            }

            MinionWorkStrategy strategy = strategies.apply(minion.type().behavior());
            List<ItemStack> yields = strategy.offlineYield(cfg, actions, ThreadLocalRandom.current(), freeUnits);
            if (yields.isEmpty()) {
                return;
            }

            // 先推进已支付指针（原子、随现有脏链路持久化），后发物品：
            // 崩溃最多少发，绝不多发
            minion.setLastActiveEpochMs(now);

            long totalUnits = yields.stream().mapToLong(ItemStack::getAmount).sum();
            minion.addToStorage(yields.toArray(new ItemStack[0]));
            for (ItemStack item : yields) {
                collection.record(minion.owner(), item.getType(), item.getAmount());
            }
            sendSummary(minion, cfg, totalUnits, yields);
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
        Player owner = Bukkit.getPlayer(minion.owner());
        if (owner == null || totalUnits <= 0) {
            return;
        }
        owner.sendMessage(com.hcs.minions.util.Messages.offlineHeader(cfg.displayName()));
        yields.stream()
                .sorted((a, b) -> Integer.compare(b.getAmount(), a.getAmount()))
                .limit(3)
                .forEach(item -> owner.sendMessage(com.hcs.minions.util.Messages.offlineDetail(
                        com.hcs.minions.util.MaterialNames.of(item.getType()), item.getAmount())));
        owner.sendMessage(com.hcs.minions.util.Messages.offlineTotal(totalUnits));
    }
}
