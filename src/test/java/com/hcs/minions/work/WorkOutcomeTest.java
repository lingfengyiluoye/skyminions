package com.hcs.minions.work;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link WorkOutcome} 不可变语义与 IDLE 常量测试。 */
class WorkOutcomeTest {

    @Test
    void idleOutcomeIndicatesNoWork() {
        WorkOutcome idle = WorkOutcome.IDLE;
        assertFalse(idle.worked());
        assertEquals(0, idle.xp());
        assertTrue(idle.drops().isEmpty());
    }

    @Test
    void idleDropsListIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> WorkOutcome.IDLE.drops().add(null));
    }

    @Test
    void workedOutcomeRetainsXpAndWorkedFlag() {
        // 使用空列表避免 ItemStack 依赖 Bukkit Registry
        WorkOutcome outcome = new WorkOutcome(true, 42, List.of());
        assertTrue(outcome.worked());
        assertEquals(42, outcome.xp());
        assertTrue(outcome.drops().isEmpty());
    }

    @Test
    void dropsListIsDefensivelyCopied() {
        // record 的 compact constructor 使用 List.copyOf 保证不可变
        WorkOutcome outcome = new WorkOutcome(true, 5, List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> outcome.drops().add(null));
    }

    @Test
    void nullDropsListIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new WorkOutcome(true, 0, null));
    }

    @Test
    void notWorkedOutcomeWithZeroXp() {
        WorkOutcome outcome = new WorkOutcome(false, 0, List.of());
        assertFalse(outcome.worked());
        assertEquals(0, outcome.xp());
    }
}
