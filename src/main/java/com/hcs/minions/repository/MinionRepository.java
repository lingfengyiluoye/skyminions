package com.hcs.minions.repository;

import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionData;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 数据访问层接口 —— 业务层唯一依赖的持久化入口。
 * 实现内部强制包含内存缓存层（ConcurrentHashMap）与虚拟线程异步落库，
 * 业务层无感知任何 IO 细节。
 */
public interface MinionRepository extends AutoCloseable {

    CompletableFuture<Optional<Minion>> find(UUID id);

    CompletableFuture<List<Minion>> findAll();

    /** 写入缓存并异步落库（write-through）。 */
    CompletableFuture<Void> save(Minion minion);

    CompletableFuture<Void> delete(UUID id);

    /** 将当前脏对象批量异步落库（由全局调度器周期调用）。 */
    void flushDirty();

    /** 收集脏仆从 ID（任意线程，不访问 Inventory）。 */
    List<UUID> collectDirtyIds();

    /** 按 ID 取缓存仆从（供 region 线程生成快照）。 */
    Minion getCached(UUID id);

    /** 认领脏标记（CAS）。 */
    boolean claimDirty(UUID id);

    /** 将 region 线程生成的快照批量异步落库。 */
    void flushSnapshots(List<MinionData> snapshots);

    /** 主线程同步冲刷（onDisable 路径，Inventory 访问安全）。 */
    void flushDirtySync();

    @Override
    void close();
}
