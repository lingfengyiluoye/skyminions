package com.hcs.minions.work;

import com.hcs.minions.model.MinionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link WorkStrategyRegistry} 策略查表与错误处理测试。 */
class WorkStrategyRegistryTest {

    /** 创建一个仅返回类型的桩策略（不执行实际工作逻辑）。 */
    private static MinionWorkStrategy stub(MinionType type) {
        return new MinionWorkStrategy() {
            @Override public MinionType type() { return type; }
            @Override public boolean canWork(WorkContext ctx) { return false; }
            @Override public WorkOutcome performWork(WorkContext ctx) { return WorkOutcome.IDLE; }
            @Override public int cooldownTicks() { return 0; }
        };
    }

    @Test
    void getReturnsRegisteredStrategy() {
        WorkStrategyRegistry registry = new WorkStrategyRegistry(
                List.of(stub(MinionType.MINER), stub(MinionType.FARMER)));
        assertSame(MinionType.MINER, registry.get(MinionType.MINER).type());
        assertSame(MinionType.FARMER, registry.get(MinionType.FARMER).type());
    }

    @Test
    void getThrowsForUnregisteredType() {
        WorkStrategyRegistry registry = new WorkStrategyRegistry(
                List.of(stub(MinionType.MINER)));
        assertThrows(IllegalStateException.class,
                () -> registry.get(MinionType.FISHER));
    }

    @Test
    void emptyRegistryThrowsOnAnyLookup() {
        WorkStrategyRegistry registry = new WorkStrategyRegistry(List.of());
        for (MinionType type : MinionType.values()) {
            assertThrows(IllegalStateException.class,
                    () -> registry.get(type),
                    "空注册表对 " + type + " 应抛异常");
        }
    }

    @Test
    void duplicateRegistrationKeepsLast() {
        // 后注册的策略覆盖先注册的（EnumMap.put 语义）
        MinionWorkStrategy first = stub(MinionType.MINER);
        MinionWorkStrategy second = stub(MinionType.MINER);
        WorkStrategyRegistry registry = new WorkStrategyRegistry(List.of(first, second));
        assertSame(second, registry.get(MinionType.MINER));
    }
}
