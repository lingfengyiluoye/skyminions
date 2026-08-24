package com.hcs.minions.work;

import com.hcs.minions.model.MinionType;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 策略注册表：类型 -> 策略的只读映射（EnumMap，键为枚举无需装箱）。
 * 调度器通过 {@link #get(MinionType)} 取策略，实现开闭原则（新增类型只需注册新策略）。
 */
public final class WorkStrategyRegistry {

    private final Map<MinionType, MinionWorkStrategy> strategies;

    public WorkStrategyRegistry(Collection<MinionWorkStrategy> all) {
        EnumMap<MinionType, MinionWorkStrategy> map = new EnumMap<>(MinionType.class);
        for (MinionWorkStrategy strategy : all) {
            map.put(strategy.type(), strategy);
        }
        this.strategies = Collections.unmodifiableMap(map);
    }

    public MinionWorkStrategy get(MinionType type) {
        MinionWorkStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalStateException("未注册的仆从类型: " + type);
        }
        return strategy;
    }
}
