package com.hcs.minions.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link OfflineRewardService#computeUnits} 纯函数测试。 */
class OfflineRewardServiceTest {

    @Test
    void computeUnitsBasic() {
        // 1 小时 = 3600000ms，cooldown 20 tick = 1 秒，效率 1.0 → 3600 周期
        assertEquals(3600, OfflineRewardService.computeUnits(3_600_000L, 20, 1.0, 100_000L));
    }

    @Test
    void computeUnitsCapped() {
        // 超长离线被 cap 截断
        assertEquals(100_000, OfflineRewardService.computeUnits(1_000_000_000L, 20, 1.0, 100_000L));
    }

    @Test
    void computeUnitsZeroWhenBelowOneCycle() {
        assertEquals(0, OfflineRewardService.computeUnits(500L, 20, 1.0, 100_000L));
    }

    @Test
    void computeUnitsEfficiencyScales() {
        // 效率 2.0 → 周期翻倍
        assertEquals(7200, OfflineRewardService.computeUnits(3_600_000L, 20, 2.0, 100_000L));
    }
}
