package com.hcs.minions.work;

import com.hcs.minions.model.MinionType;

/**
 * 仆从工作策略接口（策略模式）。
 * 调度器只面向本接口编程（canWork / performWork / cooldownTicks），
 * 严禁 if-else 堆砌类型判断 —— 类型到策略的映射由 WorkStrategyRegistry 统一查表。
 */
public interface MinionWorkStrategy {

    /** 本策略服务的仆从类型。 */
    MinionType type();

    /** 当前周期是否允许工作（燃料、环境等由 Manager 统一判断，此处只判断类型语义）。 */
    boolean canWork(WorkContext ctx);

    /**
     * 执行一次限流搜索 + 破坏，返回结果。
     * 必须在主线程/区域线程调用（内部含 setType 等方块操作）。
     */
    WorkOutcome performWork(WorkContext ctx);

    /** 两次工作之间的基础冷却（tick）。 */
    int cooldownTicks();
}
