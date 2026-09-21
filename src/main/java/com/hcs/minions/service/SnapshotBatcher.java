package com.hcs.minions.service;

import com.hcs.minions.model.MinionData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 快照批次收集器（把 {@code MinionManager#snapshotAndFlush} 里最微妙的并发规则抽成纯单元）。
 *
 * <p>背景：脏仆从的快照在各自 region 线程生成，完成时间不固定；批次有 10 秒超时。
 * 超时结算后迟到的快照<b>绝不能并进已提交的批次</b>（那批已经落库了），
 * 必须单独补刷，否则要么丢数据要么写穿。</p>
 *
 * <p>不变量（由测试守住）：</p>
 * <ul>
 *   <li>{@link #settle()} 之前的 offer 全部且恰好进入本批一次；</li>
 *   <li>{@link #settle()} 之后的 offer 一律被拒（调用方须单独补刷）；</li>
 *   <li>并发 offer/settle 不丢不快照——两者互斥，不存在「加进已结算批次之后」的孤儿。</li>
 * </ul>
 */
public final class SnapshotBatcher {

    private final List<MinionData> collected = new ArrayList<>();
    private final AtomicBoolean settled = new AtomicBoolean();

    /**
     * 提交一份快照。
     *
     * @return true = 已收进本批；false = 批次已结算，调用方必须单独补刷这份快照
     */
    public synchronized boolean offer(MinionData snapshot) {
        if (snapshot == null || settled.get()) {
            return false;
        }
        collected.add(snapshot);
        return true;
    }

    /**
     * 结算批次：取出已收集的快照，并标记此后到达的一律拒绝。
     * 幂等——重复调用第二次返回空列表。
     */
    public synchronized List<MinionData> settle() {
        if (!settled.compareAndSet(false, true)) {
            return List.of();
        }
        List<MinionData> out = List.copyOf(collected);
        collected.clear();
        return out;
    }

    /** 是否已结算（测试/诊断用）。 */
    public boolean isSettled() {
        return settled.get();
    }

    /** 当前已收集但未结算的快照数。 */
    public synchronized int pending() {
        return collected.size();
    }
}
