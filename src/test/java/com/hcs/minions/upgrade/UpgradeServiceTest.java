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
        // 超级压缩 81:1
        assertArrayEquals(new long[]{2, 0}, UpgradeService.compressCount(162, 81));
        assertArrayEquals(new long[]{1, 19}, UpgradeService.compressCount(100, 81));
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
}
