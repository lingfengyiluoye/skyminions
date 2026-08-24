package com.hcs.minions.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** {@link MinionType} 枚举查找与属性测试。 */
class MinionTypeTest {

    @Test
    void fromKeyReturnsCorrectType() {
        assertEquals(Optional.of(MinionType.MINER), MinionType.fromKey("miner"));
        assertEquals(Optional.of(MinionType.FARMER), MinionType.fromKey("farmer"));
        assertEquals(Optional.of(MinionType.LUMBERJACK), MinionType.fromKey("lumberjack"));
        assertEquals(Optional.of(MinionType.COBBLE), MinionType.fromKey("cobble"));
    }

    @Test
    void fromKeyIsCaseInsensitive() {
        assertEquals(Optional.of(MinionType.MINER), MinionType.fromKey("MINER"));
        assertEquals(Optional.of(MinionType.FISHER), MinionType.fromKey("Fisher"));
    }

    @Test
    void fromKeyReturnsEmptyForUnknown() {
        assertEquals(Optional.empty(), MinionType.fromKey("unknown"));
        assertEquals(Optional.empty(), MinionType.fromKey(""));
        assertEquals(Optional.empty(), MinionType.fromKey(null));
    }

    @Test
    void allTypesHaveUniqueKeys() {
        MinionType[] types = MinionType.values();
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                assertNotEquals(types[i].key(), types[j].key(),
                        "类型 " + types[i] + " 和 " + types[j] + " 的 key 不应重复");
            }
        }
    }

    @Test
    void allTypesHaveChineseDisplayName() {
        for (MinionType type : MinionType.values()) {
            assertNotNull(type.displayName(), type.name() + " 的 displayName 不应为 null");
            assertFalse(type.displayName().isEmpty(), type.name() + " 的 displayName 不应为空");
        }
    }

    @Test
    void allTypesHaveIconAndCategory() {
        for (MinionType type : MinionType.values()) {
            assertNotNull(type.icon(), type.name() + " 的 icon 不应为 null");
            assertNotNull(type.category(), type.name() + " 的 category 不应为 null");
        }
    }

    @Test
    void totalTypeCountMatchesDesign() {
        // 设计文档定义 7 类仆从：矿工/农夫/伐木工/钓鱼/猎魔/牧民/圆石
        assertEquals(7, MinionType.values().length);
    }
}
