package com.hcs.minions.service;

import com.hcs.minions.model.BlockLocation;

import java.util.Map;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 放置守卫（把 {@code MinionManager#place} 的占位逻辑抽成可测单元）。
 *
 * <p>Folia 下放置可能发生在不同 region 线程，三项检查必须原子完成，否则有 TOCTOU 窗口：</p>
 * <ol>
 *   <li><b>数量上限</b>：先 {@code merge +1} 预占，超限立即 {@code -1} 回滚并拒绝。
 *       若「检查」与「占位」分开做，两个 region 同时放置可双双通过检查；</li>
 *   <li><b>同格禁放</b>：{@code byLocation} 占位；</li>
 *   <li><b>最小间距</b>：与已放置仆从的水平切比雪夫距离。</li>
 * </ol>
 *
 * <p>三项共用一把锁，任何一项失败都完整回滚前几项——不会出现「计数加了但坐标没占」的
 * 半占用状态（那会慢慢吃掉玩家的仆从额度）。</p>
 */
public final class PlacementGuard {

    /** 主人 -> 仆从数。 */
    private final Map<UUID, Integer> ownerCount;
    /** 坐标 -> 仆从 id（同格禁放 + 按坐标反查）。 */
    private final Map<BlockLocation, UUID> occupied;
    private final Object lock = new Object();

    public PlacementGuard() {
        this.ownerCount = new ConcurrentHashMap<>();
        this.occupied = new ConcurrentHashMap<>();
    }

    /** 当前主人仆从数（只读快照）。 */
    public int countOf(UUID owner) {
        return ownerCount.getOrDefault(owner, 0);
    }

    /** 按坐标反查仆从 id（无则 null）。 */
    public UUID idAt(BlockLocation location) {
        return occupied.get(location);
    }

    /**
     * 尝试原子占位。
     *
     * @param owner        主人
     * @param id           仆从 id（占位后可按坐标反查）
     * @param cap          数量上限（含里程碑加成后的最终值）
     * @param location     目标坐标
     * @param minDistance  最小间距（0 = 不限制）
     * @param locationsOf  现有仆从坐标提供者（间距检查用；仅在本方法持有锁时被调用）
     * @return true = 占位成功（调用方接着完成放置）；false = 任一条件不满足，未产生任何副作用
     */
    public boolean tryReserve(UUID owner, UUID id, int cap, BlockLocation location,
                              int minDistance, Iterable<BlockLocation> locationsOf) {
        synchronized (lock) {
            int current = ownerCount.getOrDefault(owner, 0);
            if (current + 1 > cap) {
                return false; // 超限：不预占，直接拒绝
            }
            UUID previous = occupied.putIfAbsent(location, id);
            if (previous != null) {
                return false; // 同格已有仆从
            }
            if (minDistance > 0 && tooClose(location, minDistance, locationsOf)) {
                occupied.remove(location, id); // 回滚坐标占位
                return false;
            }
            ownerCount.put(owner, current + 1);
            return true;
        }
    }

    /**
     * 装配期直接占位（加载既有仆从数据用，不做 cap 检查）。
     *
     * <p>与 {@link #tryReserve} 的区别：不校验上限/间距——这些仆从是存档里已有的，
     * 不是新放置。重复坐标保留第一个 id（与旧实现 {@code putIfAbsent} 语义一致），
     * 计数仍如实累加。</p>
     */
    public void admit(UUID owner, UUID id, BlockLocation location) {
        synchronized (lock) {
            occupied.putIfAbsent(location, id);
            ownerCount.merge(owner, 1, Integer::sum);
        }
    }

    /** 释放一次放置占用（拾取/移除时调用）。 */
    public void release(UUID owner, BlockLocation location) {
        synchronized (lock) {
            occupied.remove(location);
            ownerCount.computeIfPresent(owner, (k, v) -> v <= 1 ? null : v - 1);
        }
    }

    /** 完全清空（插件关闭/重载）。 */
    public void clear() {
        synchronized (lock) {
            occupied.clear();
            ownerCount.clear();
        }
    }

    /** 水平切比雪夫距离 &lt; minDistance 即过近（跨世界不比较）。 */
    private static boolean tooClose(BlockLocation candidate, int minDistance,
                                    Iterable<BlockLocation> existing) {
        for (BlockLocation other : existing) {
            if (other == null || !other.world().equals(candidate.world())) {
                continue;
            }
            int dx = Math.abs(other.x() - candidate.x());
            if (dx >= minDistance) {
                continue;
            }
            if (Math.max(dx, Math.abs(other.z() - candidate.z())) < minDistance) {
                return true;
            }
        }
        return false;
    }
}
