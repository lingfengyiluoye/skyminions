package com.hcs.minions.repository;

import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionData;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 数据访问层接口 —— 业务层唯一依赖的持久化入口。
 * 实现内部强制包含内存缓存层（ConcurrentHashMap）与虚拟线程异步落库，
 * 业务层无感知任何 IO 细节。
 */
public interface MinionRepository extends AutoCloseable {

    /**
     * 异步读取全量持久化快照（原始数据，不构造 Bukkit 对象）。
     * 调用方须在主线程/全局线程完成 {@code Minion} 装配与 Inventory 初始化
     * （Bukkit Inventory 禁止在异步线程创建/读写）。
     */
    CompletableFuture<List<MinionData>> findAllData();

    /**
     * 将仆从纳入缓存并挂接落库通知钩子（不置脏，用于启动装载）。
     * 之后该仆从任何 {@code markDirty()} 都会自动进入脏集合参与周期落库。
     */
    void register(Minion minion);

    /** 写入缓存、挂接钩子并置脏（write-through 的登记动作，实际写入由批量落库执行）。 */
    CompletableFuture<Void> save(Minion minion);

    CompletableFuture<Void> delete(UUID id);

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
