package com.hcs.minions.upgrade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link UpgradeService} 纯函数测试（压缩换算 / 面积扩半径）。 */
class UpgradeServiceTest {

    @Test
    void compressExactDivision() {
        assertArrayEquals(new long[]{7, 0}, UpgradeService.compressCount(63, 9));
    }

    @Test
    void compressWithRemainder() {
        assertArrayEquals(new long[]{7, 2}, UpgradeService.compressCount(65, 9));
    }

    @Test
    void compressBelowRatio() {
        assertArrayEquals(new long[]{0, 8}, UpgradeService.compressCount(8, 9));
    }

    @Test
    void superCompactorRatio() {
        // 仓储级超级压缩按附魔资源 ratio 换算（主流 160:1）
        assertArrayEquals(new long[]{2, 0}, UpgradeService.compressCount(320, 160));
        assertArrayEquals(new long[]{1, 20}, UpgradeService.compressCount(180, 160));
        // 不足比例不压（全仓总量 < ratio 时保留散装）
        assertArrayEquals(new long[]{0, 159}, UpgradeService.compressCount(159, 160));
        // 末影珍珠附魔资源为 32:1
        assertArrayEquals(new long[]{2, 0}, UpgradeService.compressCount(64, 32));
    }

    @Test
    void expanderModuleRaisesRadius() {
        // 装了范围扩展：5x5 → 7x7（半径 2 → 3）
        assertEquals(3, UpgradeService.radiusWithExpander(2, true));
    }

    @Test
    void noExpanderKeepsBaseRadius() {
        // 未装范围扩展：半径原样返回（GUI 展示与实际工作共用此口径）
        assertEquals(2, UpgradeService.radiusWithExpander(2, false));
    }

    @Test
    void radiusNoBonusKeepsRadius() {
        // areaBonus = 1.0 时 5x5 边长 5 不变，半径 2 不变
        assertEquals(2, UpgradeService.radiusForArea(2, 1.0));
    }

    @Test
    void radiusFivePercentExpandsSideToOdd() {
        // 5x5 面积 25 → 放大 1.05 = 26.25 → 边长 ceil(√26.25)=6 → 偶数补 7 → 半径 3
        assertEquals(3, UpgradeService.radiusForArea(2, 1.05));
    }

    @Test
    void enchantedHopperTakesPrecedenceOverBudget() {
        // 同时装备两档漏斗时取更高档（附魔 90% > 简易 50%），对齐 Hypixel Hopper 家族
        assertEquals(0.90, UpgradeService.hopperRatioOf(true, true), 1e-9);
        assertEquals(0.90, UpgradeService.hopperRatioOf(false, true), 1e-9);
    }

    @Test
    void budgetHopperFiftyPercent() {
        assertEquals(0.50, UpgradeService.hopperRatioOf(true, false), 1e-9);
    }

    @Test
    void noHopperMeansNoInstantSell() {
        // 未装漏斗返回 0（不即时售卖，走满仓全价路径）
        assertEquals(0.0, UpgradeService.hopperRatioOf(false, false), 1e-9);
    }

    @Test
    void storageModulesGrantExpectedBonusSlots() {
        // 储物箱模块额外存储格：小/中/大 = +6/+12/+18（对齐 Hypixel Storage 升级）
        assertEquals(6, MinionUpgradeType.STORAGE_SMALL.bonusStorageSlots());
        assertEquals(12, MinionUpgradeType.STORAGE_MEDIUM.bonusStorageSlots());
        assertEquals(18, MinionUpgradeType.STORAGE_LARGE.bonusStorageSlots());
    }

    @Test
    void nonStorageModulesGrantNoBonusSlots() {
        // 非储物箱模块不提供额外存储格
        assertEquals(0, MinionUpgradeType.AUTO_SMELTER.bonusStorageSlots());
        assertEquals(0, MinionUpgradeType.BUDGET_HOPPER.bonusStorageSlots());
        assertEquals(0, MinionUpgradeType.CORRUPT_SOIL.bonusStorageSlots());
    }
}
