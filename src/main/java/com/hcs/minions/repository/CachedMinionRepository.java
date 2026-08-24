package com.hcs.minions.repository;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionData;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.Logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 *   <li>{@link #dirty}：ConcurrentHashMap.newKeySet，脏标记集合，驱动批量异步落库。</li>
 *   <li>所有阻塞 SQL 经由 {@link AsyncExecutor}（虚拟线程）执行，主线程零阻塞。</li>
 * </ul>
 */
public final class CachedMinionRepository implements MinionRepository {

    private final ConcurrentHashMap<UUID, Minion> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    private final MinionStore store;
    private final AsyncExecutor async;
    private final PluginConfig config;

    public CachedMinionRepository(MinionStore store, AsyncExecutor async, PluginConfig config) {
        this.store = store;
        this.async = async;
        this.config = config;
    }

    @Override
    public CompletableFuture<Optional<Minion>> find(UUID id) {
        Minion hit = cache.get(id);
        if (hit != null) {
            return CompletableFuture.completedFuture(Optional.of(hit));
        }
        // 缓存未命中：异步回源，命中的结果写入缓存
        return async.submit(() -> store.select(id).map(this::toMinion))
                .thenApply(opt -> {
                    opt.ifPresent(m -> cache.putIfAbsent(m.id(), m));
                    return opt;
                });
    }

    @Override
    public CompletableFuture<List<Minion>> findAll() {
        if (!cache.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>(cache.values()));
        }
        return async.submit(store::selectAll).thenApply(rows -> {
            List<Minion> out = new ArrayList<>(rows.size());
            for (MinionData d : rows) {
                Minion m = toMinion(d);
                cache.put(m.id(), m);
                out.add(m);
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Void> save(Minion minion) {
        cache.put(minion.id(), minion);
        dirty.add(minion.id());
        minion.markDirty();
        return CompletableFuture.completedFuture(null); // 实际写入由 flushDirty 批量异步执行
    }

    @Override
    public CompletableFuture<Void> delete(UUID id) {
        cache.remove(id);
        dirty.remove(id);
        return async.run(() -> store.delete(id));
    }

    /**
     * 脏数据落库（两阶段，线程安全）。
     *
     * <p>阶段一 {@link #collectDirtyIds()}：仅返回脏 ID 与认领状态，不触碰 Inventory，
     * 可在任意线程调用。阶段二由 {@link MinionManager} 在每个仆从的 region 线程调用
     * {@code minion.toData()} 生成快照（Inventory 访问必须 region 线程），随后把快照交给
     * {@link #flushSnapshots} 异步落库。</p>
     *
     * <p>这样消除了"异步线程直接遍历 Bukkit Inventory"的竞态（数据丢失级）。</p>
     */

    /** 收集需要落库的脏仆从 ID（任意线程安全，不访问 Inventory）。 */
    public List<UUID> collectDirtyIds() {
        List<UUID> out = new ArrayList<>();
        for (UUID id : List.copyOf(dirty)) {
            Minion minion = cache.get(id);
            if (minion == null) {
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
                try {
                    store.upsert(snapshot);
                } catch (Exception e) {
                    Logs.error("仆从落库失败，已重新置脏等待重试: " + snapshot.id(), e);
                    Minion minion = cache.get(snapshot.id());
                    if (minion != null) {
                        minion.markDirty();
                    }
                    dirty.add(snapshot.id());
                }
            });
        }
    }

    @Override
    public void flushDirty() {
        // 兜底：当无 region 调度上下文时（理论上不应被直接调用），退化为收集+提示。
        // 正常路径由 MinionManager 在 region 线程生成快照后调用 flushSnapshots。
        Logs.warn("flushDirty() 被直接调用：请改用 MinionManager 的快照收集路径以保证 Inventory 线程安全");
        List<MinionData> snapshots = new ArrayList<>();
        for (UUID id : collectDirtyIds()) {
            if (!claimDirty(id)) {
                continue;
            }
            Minion minion = cache.get(id);
            if (minion != null) {
                snapshots.add(minion.toData());
            }
        }
        flushSnapshots(snapshots);
    }

    private Minion toMinion(MinionData d) {
        MinionType type = MinionType.fromKey(d.type()).orElse(MinionType.MINER);
        MinionTypeConfig typeConfig = config.type(type);
        Minion minion = Minion.fromData(d);
        if (typeConfig != null) {
            minion.refresh(typeConfig);
        }
        return minion;
    }

    /**
     * 主线程同步冲刷（onDisable 路径）。
     *
     * <p>必须在主线程（或能保证 Inventory 独占的 region 线程）调用——内部直接遍历
     * Inventory 生成快照。{@link #close()} 前应先关闭所有打开的 Minion GUI，避免
     * 冲刷期间玩家仍在交互导致读到中间态（修复 P0-3）。</p>
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
            } catch (Exception e) {
                Logs.error("关闭时落库失败: " + id, e);
            }
            dirty.remove(id);
        }
    }

    @Override
    public void close() {
        // 关闭前同步冲刷脏数据，避免关闭连接池后异步写失败导致丢数据
        flushDirtySync();
        store.close();
    }
}
