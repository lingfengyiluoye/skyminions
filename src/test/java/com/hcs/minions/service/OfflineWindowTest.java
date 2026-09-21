package com.hcs.minions.service;

import com.hcs.minions.config.OfflineProductionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OfflineWindow} 回归测试——守住「同一段闲置时间不会被支付两次」。
 *
 * <p>这是离线收益系统的根不变量：指针推进与补发若不同步，玩家反复上下线就能
 * 把同一小时卖多次（刷物品），或因指针过早推进而丢一整段收益。</p>
 */
class OfflineWindowTest {

    private static final long T0 = 1_700_000_000_000L; // 固定基准时刻，避免依赖真实时钟

    private static OfflineProductionConfig cfg(int maxHours, int ratePercent, int minSeconds) {
        return new OfflineProductionConfig(true, maxHours, ratePercent, minSeconds, false);
    }

    @Test
    void belowMinSecondsProducesNothingButAdvancesPointer() {
        OfflineWindow.Settlement s = OfflineWindow.settle(T0, T0 + 60_000L, cfg(24, 100, 180));
        assertFalse(s.shouldProduce(), "低于 min-seconds 不得产出");
        assertEquals(0L, s.cappedSec());
        // 指针仍必须推进：否则反复上下线可把小窗口攒成大窗口
        assertEquals(T0 + 60_000L, s.newPointer(), "不产出也要推进指针");
    }

    @Test
    void payableWindowUsesCappedSeconds() {
        // 离线 2 小时，max-hours = 24 → 全额
        OfflineWindow.Settlement s = OfflineWindow.settle(T0, T0 + 7_200_000L, cfg(24, 100, 180));
        assertTrue(s.shouldProduce());
        assertEquals(7200L, s.cappedSec());
        assertEquals(7_200_000L, s.idleMs());
    }

    @Test
    void maxHoursCapsTheWindow() {
        // 离线 48 小时，max-hours = 24 → 只结算 24 小时
        OfflineWindow.Settlement s = OfflineWindow.settle(T0, T0 + 172_800_000L, cfg(24, 100, 180));
        assertTrue(s.shouldProduce());
        assertEquals(24 * 3600L, s.cappedSec(), "超出 max-hours 的部分不得结算");
        // 但指针推到 now：超出的部分直接作废，不会累积到下次
        assertEquals(T0 + 172_800_000L, s.newPointer());
    }

    @Test
    void settlingTwiceAtSameMomentPaysNothingTwice() {
        OfflineProductionConfig cfg = cfg(24, 100, 180);
        long later = T0 + 3_600_000L;
        OfflineWindow.Settlement first = OfflineWindow.settle(T0, later, cfg);
        assertTrue(first.shouldProduce());
        // 用第一次返回的指针再结算同一时刻：窗口必须归零
        OfflineWindow.Settlement second = OfflineWindow.settle(first.newPointer(), later, cfg);
        assertEquals(0L, second.idleMs(), "第二次结算的窗口必须为 0");
        assertFalse(second.shouldProduce(), "同一时刻不得重复产出");
    }

    @Test
    void idempotencyHoldsForEveryConfiguration() {
        // 遍历若干配置组合，幂等性必须无一失效
        long[] offsets = {0L, 179_000L, 180_000L, 3_600_000L, 172_800_000L, 999_999_999L};
        for (int maxHours : new int[]{1, 24, 72}) {
            for (int minSeconds : new int[]{0, 180, 3600}) {
                OfflineProductionConfig cfg = cfg(maxHours, 100, minSeconds);
                for (long offset : offsets) {
                    assertTrue(OfflineWindow.isIdempotentAt(T0, T0 + offset, cfg),
                            "幂等性失效: maxHours=" + maxHours + " minSeconds=" + minSeconds
                                    + " offset=" + offset);
                }
            }
        }
    }

    @Test
    void clockGoingBackwardsDoesNotProduceNegativeWindow() {
        // 服务器回拨/时区异常：idle 不为负，也不产出
        OfflineWindow.Settlement s = OfflineWindow.settle(T0, T0 - 500_000L, cfg(24, 100, 180));
        assertEquals(0L, s.idleMs(), "时钟回拨不得产生负窗口");
        assertFalse(s.shouldProduce());
        assertEquals(T0 - 500_000L, s.newPointer(), "指针仍跟随 now（不回拨时才有意义）");
    }

    @Test
    void exactlyMinSecondsIsPayable() {
        OfflineWindow.Settlement s = OfflineWindow.settle(T0, T0 + 180_000L, cfg(24, 100, 180));
        assertTrue(s.shouldProduce(), "达到阈值就应结算（>= 而非 >）");
        assertEquals(180L, s.cappedSec());
    }
}
