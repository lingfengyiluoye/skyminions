package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.model.BlockLocation;
import com.hcs.minions.model.Minion;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.EnchantedResource;
import com.hcs.minions.util.Logs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动售卖：主线程快照解锁槽位 -> 计价 -> 异步 Vault 加款 -> 成功后回主线程
 * 仅扣减解锁槽位里被售出的部分（不误删按钮/燃料槽/锁定槽）。
 */
public final class SellService {

    private final EconomyService economy;
    private final AsyncExecutor async;
    private final ConfigProvider config;
    private final JavaPlugin plugin;
    private final Map<java.util.UUID, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    public SellService(EconomyService economy, AsyncExecutor async, ConfigProvider config, JavaPlugin plugin) {
        this.economy = economy;
        this.async = async;
        this.config = config;
        this.plugin = plugin;
    }

    /**
     * 仆从被拾取/移除时清理其在途售卖闸门，避免 {@link #inFlight} 随仆从增删无限增长。
     * 由 {@code MinionManager.remove} 调用。
     */
    public void forget(java.util.UUID minionId) {
        inFlight.remove(minionId);
    }

    /**
     * 一键出售（全价）：先扣物（region 线程，读取实际物品），扣减成功后才异步加款。
     *
     * <p>修复 P2-2 刷钱窗口：旧实现「先加款后扣物」，玩家在加款成功到扣物之间取走物品
     * 会导致多卖钱但未扣物。现在顺序反转——先在 region 线程从 Inventory 实际取出物品
     *（以取出的真实物品为准计算金额），取到才加款，杜绝中间态。</p>
     */
    public CompletableFuture<Long> sellAll(Minion minion) {
        return sellAll(minion, 1.0);
    }

    /**
     * 按价格系数出售仓库全部产物（{@code priceRatio}：1.0=全价满仓卖，0.9/0.5=漏斗折价即时卖）。
     * 扣物与加款的原子顺序、失败恢复、重入闸门与全价路径完全一致。
     *
     * @param priceRatio 价格系数（(0,1]），≤0 视为不售卖
     */
    public CompletableFuture<Long> sellAll(Minion minion, double priceRatio) {
        if (!economy.isEnabled() || priceRatio <= 0) {
            return CompletableFuture.completedFuture(0L);
        }
        BlockLocation loc = minion.location();
        Location center = loc.toLocation();
        if (center == null) {
            return CompletableFuture.completedFuture(0L);
        }
        AtomicBoolean gate = inFlight.computeIfAbsent(minion.id(), ignored -> new AtomicBoolean());
        if (!gate.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0L);
        }
        CompletableFuture<Long> result = new CompletableFuture<>();
        // 在仆从所在 region 线程：从 Inventory 实际读取物品并计算金额
        Bukkit.getRegionScheduler().run(plugin, center, task -> {
            // storageItems() 只含解锁存储槽（已排除 GUI 装饰/燃料/按钮/锁定占位槽）
            List<ItemStack> items = minion.storageItems();
            if (items.isEmpty()) {
                gate.set(false);
                result.complete(0L);
                return;
            }
            // 附魔资源按 ratio 折算回散装单位计价（附魔煤炭=160 煤），
            // 否则压缩后价值蒸发 99.4%，玩家装超级压缩反而亏钱
            long totalUnits = 0;
            for (ItemStack item : items) {
                totalUnits += EnchantedResource.parse(item)
                        .map(r -> (long) item.getAmount() * r.ratio())
                        .orElse((long) item.getAmount());
            }
            var typeConfig = config.get().type(minion.type());
            if (typeConfig == null) {
                gate.set(false);
                result.complete(0L);
                return;
            }
            double perUnit = typeConfig.sellPricePerUnit() * priceRatio;
            long amount = economy.priceCents(totalUnits, perUnit);
            if (amount <= 0) {
                gate.set(false);
                result.complete(0L);
                return;
            }
            // 先扣物（region 线程原子执行：取走后才加款）
            minion.removeItems(items);
            // 扣物成功后异步加款；失败时回到仆从所在 region 尝试恢复原物品。
            try {
                economy.depositCents(minion.owner(), amount).whenComplete((success, err) -> {
                if (err != null || !Boolean.TRUE.equals(success)) {
                    Logs.error("售卖加款失败，正在恢复已扣物品 owner={} amount={}", minion.owner(), amount, err);
                    Bukkit.getRegionScheduler().run(plugin, center, ignored -> {
                        try {
                            Map<Integer, ItemStack> leftovers = minion.addToStorage(items.toArray(new ItemStack[0]));
                            dropAround(center, leftovers);
                        } finally {
                            gate.set(false);
                            result.complete(0L);
                        }
                    });
                    return;
                }
                gate.set(false);
                result.complete(amount);
                });
            } catch (RuntimeException ex) {
                Logs.error("提交售卖入账任务失败，正在恢复已扣物品 owner={} amount={}", minion.owner(), amount, ex);
                Map<Integer, ItemStack> leftovers = minion.addToStorage(items.toArray(new ItemStack[0]));
                dropAround(center, leftovers);
                gate.set(false);
                result.complete(0L);
            }
        });
        return result;
    }

    /** 在仆从所在位置还原溢出物品；区域已卸载时无法安全掉落，仅记日志（物品已尽力回仓）。 */
    private static void dropAround(Location center, Map<Integer, ItemStack> leftovers) {
        if (leftovers.isEmpty()) {
            return;
        }
        org.bukkit.World world = center.getWorld();
        if (world == null) {
            Logs.warn("售卖回滚时所在区域已卸载，无法掉落溢出物品 count={}", leftovers.size());
            return;
        }
        for (ItemStack leftover : leftovers.values()) {
            world.dropItemNaturally(center.clone().add(0.5, 1.0, 0.5), leftover);
        }
    }
}
