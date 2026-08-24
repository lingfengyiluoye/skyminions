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
 *   <li>{@link #dirty}：ConcurrentHashMap.newKeySet，脏标记集合，驱动批量异步落库；
 *       由 {@link Minion#markDirty()} 经钩子自动登记，运行期产出与 GUI 操作同链路持久化。</li>
 *   <li>{@link #idLocks}：按 id 的条带锁，串行化同一仆从的 upsert/delete，
 *       消除虚拟线程乱序导致的「已删除仆从被旧快照复活」竞态。</li>
 *   <li>所有阻塞 SQL 经由 {@link AsyncExecutor}（虚拟线程）执行，主线程零阻塞。</li>
 * </ul>
 */
public final class CachedMinionRepository implements MinionRepository {

    private final ConcurrentHashMap<UUID, Minion> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    /** 按 id 串行化数据库写操作（upsert vs delete 的顺序保证）。 */
    private final ConcurrentHashMap<UUID, Object> idLocks = new ConcurrentHashMap<>();

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

    @Override
    public void register(Minion minion) {
        cache.put(minion.id(), minion);
        // 挂接钩子：此后任何 markDirty() 都会自动进入脏集合（P0-2 修复的核心链路）
        minion.setDirtyHook(() -> dirty.add(minion.id()));
    }

    @Override
    public CompletableFuture<Void> save(Minion minion) {
        register(minion);
        dirty.add(minion.id());
        minion.markDirty();
        return CompletableFuture.completedFuture(null); // 实际写入由 flushSnapshots 批量异步执行
    }

    @Override
    public CompletableFuture<Void> delete(UUID id) {
        Object lock = lockFor(id);
        synchronized (lock) {
            cache.remove(id);
            dirty.remove(id);
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

    /** 收集需要落库的脏仆从 ID（任意线程安全，不访问 Inventory）。 */
    public List<UUID> collectDirtyIds() {
        List<UUID> out = new ArrayList<>();
        for (UUID id : List.copyOf(dirty)) {
            if (!cache.containsKey(id)) {
                dirty.remove(id);
                continue;
            }
            out.add(id);
        }
        return out;
    }

    /** 按 ID 取缓存中的仆从（供 region 线程生成快照）。 */
    public Minion getCached(UUID id) {
        return cache.get(id);
    }

    /** 认领某仆从的脏标记（CAS，成功后本轮落库不丢修改）。 */
    public boolean claimDirty(UUID id) {
        Minion minion = cache.get(id);
        if (minion == null) {
            dirty.remove(id);
            return false;
        }
        if (!minion.tryClaimFlush()) {
            dirty.remove(id);
            return false;
        }
        dirty.remove(id);
        return true;
    }

    /** 将 region 线程已生成的快照批量异步落库。 */
    public void flushSnapshots(List<MinionData> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        for (MinionData snapshot : snapshots) {
            async.run(() -> {
                Object lock = lockFor(snapshot.id());
                synchronized (lock) {
                    try {
                        // 快照生成后若仆从已被拾取/移除（delete 同步清 cache），丢弃该次 upsert；
                        // 且与 delete 共享同一把 id 锁：两者必然全序执行，杜绝「先删后被旧快照覆盖」复活
                        if (!cache.containsKey(snapshot.id())) {
                            return;
                        }
                        store.upsert(snapshot);
                    } catch (Exception e) {
                        Logs.error("仆从落库失败，已重新置脏等待重试: " + snapshot.id(), e);
                        Minion minion = cache.get(snapshot.id());
                        if (minion != null) {
                            minion.markDirty();
                        }
                        dirty.add(snapshot.id());
                    }
                }
            });
        }
    }

    /**
     * 主线程同步冲刷（onDisable 路径）。
     *
     * <p>必须在主线程（或能保证 Inventory 独占的 region 线程）调用——内部直接遍历
     * Inventory 生成快照。调用前应先关闭所有打开的 Minion GUI，避免
     * 冲刷期间玩家仍在交互导致读到中间态。</p>
     */
    @Override
    public void flushDirtySync() {
        for (UUID id : List.copyOf(dirty)) {
            Minion minion = cache.get(id);
            if (minion == null) {
                dirty.remove(id);
                continue;
            }
            try {
                store.upsert(minion.toData());
                minion.markClean();
                dirty.remove(id);
            } catch (Exception e) {
                // 失败时保留脏标记（与 flushSnapshots 异步路径口径一致），
                // 避免瞬时 IO 错误（如 Windows 文件锁）直接丢数据
                Logs.error("关闭时落库失败，脏标记保留待重试: " + id, e);
            }
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
