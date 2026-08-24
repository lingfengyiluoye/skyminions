package com.hcs.minions.work;

import com.hcs.minions.model.MinionBehavior;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link WorkStrategyRegistry} 策略查表与错误处理测试。 */
class WorkStrategyRegistryTest {

    /** 创建一个仅返回行为的桩策略（不执行实际工作逻辑）。 */
    private static MinionWorkStrategy stub(MinionBehavior behavior) {
        return new MinionWorkStrategy() {
            @Override public MinionBehavior behavior() { return behavior; }
            @Override public boolean canWork(WorkContext ctx) { return false; }
            @Override public WorkOutcome performWork(WorkContext ctx) { return WorkOutcome.IDLE; }
        };
    }

    @Test
    void getReturnsRegisteredStrategy() {
        WorkStrategyRegistry registry = new WorkStrategyRegistry(
                List.of(stub(MinionBehavior.MINING), stub(MinionBehavior.FARMING)));
        assertSame(MinionBehavior.MINING, registry.get(MinionBehavior.MINING).behavior());
        assertSame(MinionBehavior.FARMING, registry.get(MinionBehavior.FARMING).behavior());
    }

    @Test
    void getThrowsForUnregisteredType() {
        WorkStrategyRegistry registry = new WorkStrategyRegistry(
                List.of(stub(MinionBehavior.MINING)));
        assertThrows(IllegalStateException.class,
                () -> registry.get(MinionBehavior.FISHING));
    }

    @Test
    void emptyRegistryThrowsOnAnyLookup() {
        WorkStrategyRegistry registry = new WorkStrategyRegistry(List.of());
        for (MinionBehavior behavior : MinionBehavior.values()) {
            assertThrows(IllegalStateException.class,
                    () -> registry.get(behavior),
                    "空注册表对 " + behavior + " 应抛异常");
        }
    }

    @Test
    void duplicateRegistrationKeepsLast() {
        // 后注册的策略覆盖先注册的（EnumMap.put 语义）
        MinionWorkStrategy first = stub(MinionBehavior.MINING);
        MinionWorkStrategy second = stub(MinionBehavior.MINING);
        WorkStrategyRegistry registry = new WorkStrategyRegistry(List.of(first, second));
        assertSame(second, registry.get(MinionBehavior.MINING));
    }
}
