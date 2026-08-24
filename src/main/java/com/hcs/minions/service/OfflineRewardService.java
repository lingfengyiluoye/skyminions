package com.hcs.minions.service;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.repository.MinionRepository;
import com.hcs.minions.util.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 离线收益结算：按时间差批量模拟产出，一次性写入真实仓库，绝不逐 tick 回放。
 * 结算后立即更新 lastActive 并异步写库，幂等防重复发放。
 */
public final class OfflineRewardService {

    private final PluginConfig config;
    private final MinionRepository repository;

    public OfflineRewardService(PluginConfig config, MinionRepository repository) {
        this.config = config;
        this.repository = repository;
    }

    public void grantOnJoin(Player player, List<Minion> minions) {
        if (!config.offline().enabled() || minions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long capMs = config.offline().maxHours() * 3600_000L;
        long totalGranted = 0;
        long earliestLastActive = Long.MAX_VALUE; // 记录最早离线时间，用于消息展示

        for (Minion minion : minions) {
            if (!player.getUniqueId().equals(minion.owner())) {
                continue;
            }
            long last = minion.lastActiveEpochMs();
            if (last <= 0) {
                minion.setLastActiveEpochMs(now);
                repository.save(minion);
                continue;
            }
            long elapsed = now - last;
            if (elapsed <= 0) {
                continue;
            }
            earliestLastActive = Math.min(earliestLastActive, last);
            long capped = Math.min(elapsed, capMs);
            long units = computeUnits(minion, capped);
            if (units > 0) {
                Material product = representativeProduct(minion);
                int max = product.getMaxStackSize();
                long remaining = Math.min(units, 100_000L);
                long granted = 0;
                while (remaining > 0) {
                    int chunk = (int) Math.min(remaining, max);
                    Map<Integer, ItemStack> leftover = minion.addToStorage(new ItemStack(product, chunk));
                    long added = chunk - leftover.values().stream().mapToLong(ItemStack::getAmount).sum();
                    granted += added;
                    if (added < chunk) {
                        break; // 仓库已满
                    }
                    remaining -= chunk;
                }
                totalGranted += granted;
            }
            minion.setLastActiveEpochMs(now);
            repository.save(minion);
        }

        if (totalGranted > 0) {
            // 离线分钟数按最早离线时间计算（修复 P1-3：之前硬编码 0）
            long minutes = earliestLastActive == Long.MAX_VALUE ? 0 : Math.max(0, (now - earliestLastActive) / 60000);
            player.sendMessage(Messages.offlineReward(totalGranted, minutes));
        }
    }

    private long computeUnits(Minion minion, long elapsedMs) {
        MinionTypeConfig tc = config.type(minion.type());
        double efficiency = minion.efficiency(tc) * config.offline().rateMultiplier();
        return computeUnits(elapsedMs, tc.cooldownTicks(), efficiency, 100_000L);
    }

    /** 纯函数：按时间差折算离线产出数量（上限截断），供单测验证。 */
    static long computeUnits(long elapsedMs, int cooldownTicks, double efficiency, long cap) {
        double cycleSeconds = Math.max(1.0, cooldownTicks / 20.0);
        long cycles = (long) ((elapsedMs / 1000.0) / cycleSeconds * efficiency);
        return Math.min(cycles, cap);
    }

    private Material representativeProduct(Minion minion) {
        return config.type(minion.type()).product();
    }
}
