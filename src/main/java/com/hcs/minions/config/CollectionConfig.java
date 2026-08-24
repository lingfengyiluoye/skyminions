package com.hcs.minions.config;

import java.util.Set;

/**
 * Collection 里程碑配置（对齐 Hypixel SkyBlock 采集里程碑玩法）。
 *
 * <p>每种资源独立计数：累计量每跨过一个阈值即达成一个里程碑，
 * 奖励金币（第 n 个 = {@code coinsBase * n}）；命中 {@code slotMilestones}
 * 的里程碑额外奖励仆从槽位 +1（多种资源可叠加，但总量受
 * {@code maxBonusSlots} 硬上限钳制，防止多资源叠加把经济玩坏）。</p>
 *
 * @param enabled         是否启用里程碑奖励（关闭时仅记录累计量，用于展示）
 * @param milestones      严格递增的里程碑阈值
 * @param coinsBase       金币奖励基数（第 n 个里程碑奖励 coinsBase * n）
 * @param slotMilestones  达成这些序号（1-based）的里程碑时各奖励仆从槽位 +1
 * @param maxBonusSlots   里程碑槽位加成的硬上限（多资源叠加后的总加成不超过此值）
 */
public record CollectionConfig(
        boolean enabled,
        long[] milestones,
        long coinsBase,
        Set<Integer> slotMilestones,
        int maxBonusSlots
) {

    private static final long[] DEFAULT_MILESTONES = {50, 100, 250, 500, 1000, 2500, 5000, 10000};

    public CollectionConfig {
        milestones = milestones == null || milestones.length == 0
                ? DEFAULT_MILESTONES.clone()
                : milestones.clone();
        slotMilestones = Set.copyOf(slotMilestones == null ? Set.of() : slotMilestones);
        maxBonusSlots = Math.max(0, maxBonusSlots);
    }

    @Override
    public long[] milestones() {
        return milestones.clone();
    }

    /** 第 n 个（1-based）里程碑的阈值。 */
    public long thresholdOf(int n) {
        return milestones[Math.min(n, milestones.length) - 1];
    }

    /** 纯函数：累计量已达成的最高里程碑序号（1-based，0 = 尚未达成任何里程碑）。 */
    public int reachedIndex(long total) {
        int n = 0;
        for (long m : milestones) {
            if (total >= m) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    /** 纯函数：某资源已达成 claimedMax 个里程碑时应得的仆从槽位加成。 */
    public long bonusSlotsFor(int claimedMax) {
        if (claimedMax <= 0) {
            return 0;
        }
        return slotMilestones.stream().filter(s -> s >= 1 && s <= claimedMax).count();
    }
}
