package com.hcs.minions.repository;

import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionData;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.Logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带内存缓存的仓库实现。
 *
 * <p>并发模型：
 * <ul>
 *   <li>{@link #cache}：ConcurrentHashMap，O(1) 平均查找，业务层高频读取无锁。</li>
 *   <li>脏标记：Minion 自带的 AtomicBoolean（唯一真相源），collectDirtyIds 扫缓存收集；
 *       运行期产出与 GUI 操作都经 Minion#markDirty() 置位，同一条持久化链路。</li>
 *   <li>{@link #idLocks}：按 id 的条带锁，串行化同一仆从的 upsert/delete，
 *       消除虚拟线程乱序导致的「已删除仆从被旧快照复活」竞态。</li>
 *   <li>所有阻塞 SQL 经由 {@link AsyncExecutor}（虚拟线程）执行，主线程零阻塞。</li>
 * </ul>
 */
public final class CachedMinionRepository implements MinionRepository {

    private final ConcurrentHashMap<UUID, Minion> cache = new ConcurrentHashMap<>();
    /** 按 id 串行化数据库写操作（upsert vs delete 的顺序保证）。 */
    private final ConcurrentHashMap<UUID, Object> idLocks = new ConcurrentHashMap<>();
    /** 关闭阶段禁止旧快照在最终同步刷库之后再次写入。 */
    private volatile boolean closing;

    private final MinionStore store;
    private final AsyncExecutor async;

    public CachedMinionRepository(MinionStore store, AsyncExecutor async) {
        this.store = store;
        this.async = async;
    }

    @Override
    public CompletableFuture<List<MinionData>> findAllData() {
        return async.submit(store::selectAll);
    }

    /**
     * 入缓存。不再需要额外的「脏 ID 集合」——{@code Minion} 自带的
     * {@code AtomicBoolean dirty} 就是唯一真相源（单一职责），
     * {@link #collectDirtyIds()} 直接扫缓存里的标志位。
     */
    @Override
    public void register(Minion minion) {
        cache.put(minion.id(), minion);
    }

    @Override
    public CompletableFuture<Void> save(Minion minion) {
        register(minion);
        minion.markDirty();
        return CompletableFuture.completedFuture(null); // 实际写入由 flushSnapshots 批量异步执行
    }

    @Override
    public CompletableFuture<Void> delete(UUID id) {
        Object lock = lockFor(id);
        synchronized (lock) {
            cache.remove(id);
            return async.run(() -> {
                synchronized (lock) {
                    try {
                        store.delete(id);
                    } finally {
                        idLocks.remove(id, lock);
                    }
                }
            });
        }
    }

    /**
     * 收集需要落库的脏仆从 ID（任意线程安全，不访问 Inventory）。
     *
     * <p>单一脏标记：直接扫缓存里的 {@code Minion#isDirty()}（AtomicBoolean 读），
     * 不再维护第二份 UUID 集合。每 5 秒一次、N 次 volatile 读，开销可忽略，
     * 却消掉了「两处状态需同步维护」这类 bug 源。</p>
     */
    public List<UUID> collectDirtyIds() {
        List<UUID> out = new ArrayList<>();
        for (Minion minion : cache.values()) {
            if (minion.isDirty()) {
                out.add(minion.id());
            }
        }
        return out;
    }

    /** 按 ID 取缓存中的仆从（供 region 线程生成快照）。 */
    public Minion getCached(UUID id) {
        return cache.get(id);
    }

    /**
     * 认领某仆从的脏标记（CAS，成功后本轮落库不丢修改）。
     *
     * <p>认领成功后 {@code Minion.dirty} 已置 false，若此刻崩溃/关闭，
     * 该仆从不会出现在 {@link #collectDirtyIds()} 里——所以调用方必须保证
     * 认领后一定走到 {@link #flushSnapshots} 或 {@link #flushDirtySync}，
     * 这就是 MinionManager 把批次提升为字段、stop() 强制结算的原因。</p>
     */
    public boolean claimDirty(UUID id) {
        Minion minion = cache.get(id);
        return minion != null && minion.tryClaimFlush();
    }

    /**
     * 将 region 线程已生成的快照批量落库（单事务）。
     *
     * <p>整批一个异步任务 + 存储层单事务：N 个脏仆从从 N 次自动提交变成 1 次。
     * 任一条失败整体回滚，由 catch 重新置脏，下轮重试——不会出现写了一半的中间态。</p>
     *
     * <p>删除语义：快照生成后仆从可能已被拾取（delete 同步清 cache），
     * 这批快照对应 id 已不在 cache 的会被存储层写入但无关紧要——
     * 真正的「复活」防护由 {@code MinionManager.remove()} 先清 cache 再 delete、
     * 且 Minsnapshot 路径与 delete 共享 id 锁的全序保证。</p>
     */
    public void flushSnapshots(List<MinionData> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        List<MinionData> batch = List.copyOf(snapshots);
        async.run(() -> {
            if (closing) {
                requeueAll(batch);
                return;
            }
            try {
                store.upsertAll(batch);
            } catch (Exception e) {
                Logs.error("仆从批量落库失败（{} 份），已重新置脏等待重试", batch.size(), e);
                requeueAll(batch);
            }
        });
    }

    /** 整批重新置脏（任一条写失败都不丢数据）。 */
    private void requeueAll(List<MinionData> batch) {
        for (MinionData snapshot : batch) {
            Minion minion = cache.get(snapshot.id());
            if (minion != null) {
                minion.markDirty();
            }
        }
    }

    /**
     * 主线程同步冲刷（onDisable 路径）。
     *
     * <p>必须在主线程（或能保证 Inventory 独占的 region 线程）调用——内部直接遍历
     * Inventory 生成快照。调用前应先关闭所有打开的 Minion GUI，避免
     * 冲刷期间玩家仍在交互导致读到中间态。</p>
     *
     * <p>同样走单事务批量：关闭路径也不再是 N 次自动提交。</p>
     */
    @Override
    public void flushDirtySync() {
        closing = true;
        List<MinionData> batch = new ArrayList<>();
        for (Minion minion : cache.values()) {
            if (!minion.isDirty()) {
                continue;
            }
            try {
                Object lock = lockFor(minion.id());
                synchronized (lock) {
                    batch.add(minion.toData());
                    minion.markClean();
                }
            } catch (Exception e) {
                // 快照生成失败（如 Inventory 读取异常）：保留脏标记，不阻断其余仆从
                Logs.error("关闭时生成快照失败，脏标记保留: {}", minion.id(), e);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            store.upsertAll(batch);
        } catch (Exception e) {
            // 整批失败：全部恢复脏标记（与异步路径口径一致），
            // 避免瞬时 IO 错误（如 Windows 文件锁）直接丢数据
            Logs.error("关闭时批量落库失败（{} 份），脏标记全部保留", batch.size(), e);
            requeueAll(batch);
        }
    }

    @Override
    public void close() {
        // 关闭前同步冲刷脏数据，避免关闭连接池后异步写失败导致丢数据
        flushDirtySync();
        store.close();
    }

    private Object lockFor(UUID id) {
        return idLocks.computeIfAbsent(id, k -> new Object());
    }
}
