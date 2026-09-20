package com.hcs.minions.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Bars} 进度条测试：同维度分数、段数、语义色、钳制。 */
class BarsTest {

    @Test
    void halfFillsHalfSegments() {
        assertEquals("▮▮▮▮▮▯▯▯▯▯", Bars.of(5, 10));
    }

    @Test
    void fullAndOverflowAreClamped() {
        assertEquals("▮▮▮▮▮▮▮▮▮▮", Bars.of(10, 10));
        assertEquals("▮▮▮▮▮▮▮▮▮▮", Bars.of(999, 10), "超过 100% 必须钳制为满格");
    }

    @Test
    void zeroOrNegativeMaxRendersEmpty() {
        assertEquals("▯▯▯▯▯▯▯▯▯▯", Bars.of(0, 0));
        assertEquals("▯▯▯▯▯▯▯▯▯▯", Bars.of(5, 0));
        assertEquals("▯▯▯▯▯▯▯▯▯▯", Bars.of(-3, -10));
    }

    @Test
    void customSegmentCount() {
        assertEquals("▮▮▯▯", Bars.of(2, 4, 4));
    }

    @Test
    void colorsFollowOccupancySemantics() {
        // <70% 绿 / 70~95% 黄 / ≥95% 红 / 无分母 红
        assertEquals("<green>", Bars.colorOf(1, 10));
        assertEquals("<yellow>", Bars.colorOf(8, 10));
        assertEquals("<red>", Bars.colorOf(10, 10));
        assertEquals("<red>", Bars.colorOf(5, 0));
    }

    @Test
    void coloredBarWrapsFilledAndEmptySeparately() {
        String bar = Bars.colored(5, 10);
        assertTrue(bar.startsWith("<green>"), "低占用应为绿: " + bar);
        assertTrue(bar.contains("</green><dark_gray>"), "空格段应为深灰: " + bar);
        assertTrue(bar.endsWith("</dark_gray>"), bar);
        // 满格时没有深灰尾段
        assertEquals("<red>▮▮▮▮▮▮▮▮▮▮</red>", Bars.colored(10, 10));
    }

    @Test
    void tickFractionPicksMinutesOrSeconds() {
        assertEquals("45/64 分钟", Bars.fractionTicks(54000, 76800)); // 45min/64min
        assertEquals("20/30 秒", Bars.fractionTicks(400, 600));
        // 不足 1 分钟的总量按秒折算，避免出现 "0/0 分钟"
        assertEquals("5/30 秒", Bars.fractionTicks(100, 600));
    }

    @Test
    void unitFractionKeepsBothSidesSameDimension() {
        assertEquals("128/1792 件", Bars.fraction(128, 1792, "件"));
        assertEquals("2/28 格", Bars.fraction(2, 28, "格"));
        // 负值钳到 0，不出现 "-5/10"
        assertEquals("0/10 件", Bars.fraction(-5, 10, "件"));
    }
}
