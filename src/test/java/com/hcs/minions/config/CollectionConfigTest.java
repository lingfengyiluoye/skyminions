package com.hcs.minions.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Collection 里程碑纯函数测试：阈值跨越检测与槽位加成计算。
 */
class CollectionConfigTest {

    private static final CollectionConfig CFG = new CollectionConfig(
            true, new long[]{50, 100, 250, 500}, 100, Set.of(3, 5, 7), 5);

    @Test
    void reachedIndexBelowFirstMilestone() {
        assertEquals(0, CFG.reachedIndex(0));
        assertEquals(0, CFG.reachedIndex(49));
    }

    @Test
    void reachedIndexExactlyAtThreshold() {
        assertEquals(1, CFG.reachedIndex(50));
        assertEquals(2, CFG.reachedIndex(100));
        assertEquals(4, CFG.reachedIndex(500));
    }

    @Test
    void reachedIndexBetweenThresholds() {
        assertEquals(1, CFG.reachedIndex(99));
        assertEquals(2, CFG.reachedIndex(150));   // 100~250 之间 → 第 2 个已达成
        assertEquals(3, CFG.reachedIndex(251));   // 跨过 250
        assertEquals(4, CFG.reachedIndex(10000));
    }

    @Test
    void thresholdOfReturnsNthMilestone() {
        assertEquals(50, CFG.thresholdOf(1));
        assertEquals(100, CFG.thresholdOf(2));
        // 越界钳制到最后一档
        assertEquals(500, CFG.thresholdOf(99));
    }

    @Test
    void bonusSlotsCountOnlyReachedSlotMilestones() {
        assertEquals(0, CFG.bonusSlotsFor(0));
        assertEquals(0, CFG.bonusSlotsFor(2));
        assertEquals(1, CFG.bonusSlotsFor(3));   // 第 3 个里程碑 +1
        assertEquals(1, CFG.bonusSlotsFor(4));
        assertEquals(2, CFG.bonusSlotsFor(5));
        assertEquals(3, CFG.bonusSlotsFor(7));   // 3/5/7 全命中
        assertEquals(3, CFG.bonusSlotsFor(100)); // 上限即配置全部
    }

    @Test
    void milestonesDefensiveCopy() {
        long[] input = {50, 100};
        CollectionConfig cfg = new CollectionConfig(true, input, 10, Set.of(), 5);
        input[0] = 999;
        assertEquals(50, cfg.thresholdOf(1));
        // 外部拿到 accessor 数组也不能污染内部状态
        long[] leaked = cfg.milestones();
        leaked[0] = -1;
        assertEquals(50, cfg.thresholdOf(1));
    }

    @Test
    void defaultMilestonesWhenEmpty() {
        CollectionConfig cfg = new CollectionConfig(true, new long[0], 10, Set.of(), 5);
        assertEquals(8, cfg.milestones().length);
        assertEquals(50, cfg.thresholdOf(1));
    }

    @Test
    void negativeSlotsIgnored() {
        CollectionConfig cfg = new CollectionConfig(true, new long[]{10}, 10, Set.of(-1, 0, 1), 5);
        assertEquals(1, cfg.bonusSlotsFor(1));
        assertEquals(0, cfg.bonusSlotsFor(0));
    }

    @Test
    void coinRewardScalesWithIndex() {
        // 第 n 个里程碑 = coinsBase * n，由 award 逻辑使用；这里验证基数计算约定
        assertEquals(100, CFG.coinsBase() * 1);
        assertEquals(300, CFG.coinsBase() * 3);
    }

    @Test
    void thresholdOfClampsToLastWhenIndexTooLarge() {
        // thresholdOf 按 1-based 设计；越界钳制到最后一档（与 thresholdOf(99) 一致）
        assertEquals(CFG.thresholdOf(99), CFG.thresholdOf(Integer.MAX_VALUE));
    }
}
