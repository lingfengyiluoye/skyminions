package com.hcs.minions.work;

import com.hcs.minions.model.MinionBehavior;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 策略注册表：行为原型 -> 策略的只读映射（EnumMap，键为枚举无需装箱）。
 * 调度器通过 {@link #get(MinionBehavior)} 取策略，实现开闭原则
 * （新增仆从类型只需在配置里指向某个行为，新增行为才需要新策略类）。
 */
public final class WorkStrategyRegistry {

    private final Map<MinionBehavior, MinionWorkStrategy> strategies;

    public WorkStrategyRegistry(Collection<MinionWorkStrategy> all) {
        EnumMap<MinionBehavior, MinionWorkStrategy> map = new EnumMap<>(MinionBehavior.class);
        for (MinionWorkStrategy strategy : all) {
            map.put(strategy.behavior(), strategy);
        }
        this.strategies = Collections.unmodifiableMap(map);
    }

    public MinionWorkStrategy get(MinionBehavior behavior) {
        MinionWorkStrategy strategy = strategies.get(behavior);
        if (strategy == null) {
            throw new IllegalStateException("未注册的行为原型: " + behavior);
        }
        return strategy;
    }
}
