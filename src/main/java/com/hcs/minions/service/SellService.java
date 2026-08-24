package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.model.BlockLocation;
import com.hcs.minions.model.Minion;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.Logs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 自动售卖：主线程快照解锁槽位 -> 计价 -> 异步 Vault 加款 -> 成功后回主线程
 * 仅扣减解锁槽位里被售出的部分（不误删按钮/燃料槽/锁定槽）。
 */
public final class SellService {

    private final EconomyService economy;
    private final AsyncExecutor async;
    private final ConfigProvider config;
    private final JavaPlugin plugin;

    public SellService(EconomyService economy, AsyncExecutor async, ConfigProvider config, JavaPlugin plugin) {
        this.economy = economy;
        this.async = async;
        this.config = config;
        this.plugin = plugin;
    }

    /**
     * 一键出售：先扣物（region 线程，读取实际物品），扣减成功后才异步加款。
     *
     * <p>修复 P2-2 刷钱窗口：旧实现「先加款后扣物」，玩家在加款成功到扣物之间取走物品
     * 会导致多卖钱但未扣物。现在顺序反转——先在 region 线程从 Inventory 实际取出物品
     *（以取出的真实物品为准计算金额），取到才加款，杜绝中间态。</p>
     */
    public CompletableFuture<Long> sellAll(Minion minion) {
        if (!economy.isEnabled()) {
            return CompletableFuture.completedFuture(0L);
        }
        BlockLocation loc = minion.location();
        Location center = loc.toLocation();
        if (center == null) {
            return CompletableFuture.completedFuture(0L);
        }
        CompletableFuture<Long> result = new CompletableFuture<>();
        // 在仆从所在 region 线程：从 Inventory 实际读取物品并计算金额
        Bukkit.getRegionScheduler().run(plugin, center, task -> {
            // storageItems() 只含解锁存储槽（已排除 GUI 装饰/燃料/按钮/锁定占位槽）
            List<ItemStack> items = minion.storageItems();
            if (items.isEmpty()) {
                result.complete(0L);
                return;
            }
            long totalUnits = items.stream().mapToLong(ItemStack::getAmount).sum();
            double perUnit = config.get().type(minion.type()).sellPricePerUnit();
            long amount = economy.priceCents(totalUnits, perUnit);
            if (amount <= 0) {
                result.complete(0L);
                return;
            }
            // 先扣物（region 线程原子执行：取走后才加款）
            minion.removeItems(items);
            // 扣物成功后异步加款；加款失败记日志人工核查（物品已扣，无法自动回滚）
            economy.depositCents(minion.owner(), amount).whenComplete((success, err) -> {
                if (err != null || !Boolean.TRUE.equals(success)) {
                    Logs.error("售卖加款失败（物品已扣除，需人工核查）owner={} amount={}", minion.owner(), amount, err);
                    result.complete(0L);
                    return;
                }
                result.complete(amount);
            });
        });
        return result;
    }
}
