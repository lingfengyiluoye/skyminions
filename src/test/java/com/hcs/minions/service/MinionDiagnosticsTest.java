package com.hcs.minions.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MinionDiagnostics.Verdict} 的语义测试。
 *
 * <p>诊断的完整链路依赖 Bukkit（世界/玩家/策略扫描），无法在单测里跑；
 * 但「哪些结论算异常」直接决定 /minion diagnose 汇总行里的计数和排序，
 * 这条必须钉死——否则服主看到的「异常 0」可能是假的。</p>
 */
class MinionDiagnosticsTest {

    @Test
    void everyVerdictHasLabel() {
        for (MinionDiagnostics.Verdict v : MinionDiagnostics.Verdict.values()) {
            assertNotNull(v.label(), "结论必须有中文文案: " + v.name());
            assertFalse(v.label().isBlank(), "结论文案不能为空: " + v.name());
        }
    }

    @Test
    void onlyWorkingAndCooldownCountAsProducing() {
        // 冷却中 = 正常等待，算「在产出」；其余全部算异常/休眠
        assertTrue(MinionDiagnostics.Verdict.WORKING.isProducing());
        assertTrue(MinionDiagnostics.Verdict.COOLDOWN.isProducing());
        assertFalse(MinionDiagnostics.Verdict.DORMANT.isProducing(), "休眠不算产出");
        assertFalse(MinionDiagnostics.Verdict.HALTED_FULL.isProducing());
        assertFalse(MinionDiagnostics.Verdict.NO_TARGET.isProducing());
        assertFalse(MinionDiagnostics.Verdict.CONFIG_MISSING.isProducing());
        assertFalse(MinionDiagnostics.Verdict.CHUNK_UNLOADED.isProducing());
        assertFalse(MinionDiagnostics.Verdict.BLOCKED_BY_ISLAND.isProducing());
    }

    @Test
    void verdictLabelsAreDistinct() {
        // 两只仆从显示同一结论文案时，玩家无法分辨到底哪里出了问题
        var labels = new java.util.HashSet<String>();
        for (MinionDiagnostics.Verdict v : MinionDiagnostics.Verdict.values()) {
            assertTrue(labels.add(v.label()), "结论文案重复: " + v.label());
        }
    }
}
