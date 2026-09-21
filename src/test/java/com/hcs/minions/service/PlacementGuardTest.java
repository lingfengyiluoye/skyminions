package com.hcs.minions.service;

import com.hcs.minions.model.BlockLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PlacementGuard} 回归测试——守住放置的三项原子检查。
 *
 * <p>对应 Folia 下的真实竞态：两个 region 同时给同一玩家放仆从。若「检查上限」与
 * 「计数占位」分开做，两边都看到 count=cap-1，双双通过 → 超限。守卫用一把锁把
 * 计数/坐标/间距锁在一起，任何失败都完整回滚。</p>
 */
class PlacementGuardTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static BlockLocation loc(int x, int z) {
        return new BlockLocation("world", x, 64, z);
    }

    @Test
    void capIsEnforcedUnderConcurrentReservations() throws Exception {
        PlacementGuard guard = new PlacementGuard();
        int cap = 5;
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Boolean> results = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            int x = t * 100; // 互不相同的坐标，排除间距干扰
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(guard.tryReserve(OWNER, UUID.randomUUID(), cap, loc(x, 0), 0, List.of()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        long granted = results.stream().filter(b -> b).count();
        assertEquals(cap, granted, "并发下最多只能放进 cap 个（TOCTOU 必须被锁死）");
        assertEquals(cap, guard.countOf(OWNER), "计数必须与成功次数一致");
    }

    @Test
    void failedReservationLeavesNoPartialState() {
        PlacementGuard guard = new PlacementGuard();
        // 第一次成功占 (0,0)
        assertTrue(guard.tryReserve(OWNER, UUID.randomUUID(), 10, loc(0, 0), 0, List.of()));
        // 同格第二次：失败
        assertFalse(guard.tryReserve(OWNER, UUID.randomUUID(), 10, loc(0, 0), 0, List.of()));
        assertEquals(1, guard.countOf(OWNER), "失败的放置不得占用计数");

        // 间距不足：失败，且坐标占位必须回滚
        List<BlockLocation> existing = List.of(loc(0, 0));
        assertFalse(guard.tryReserve(OWNER, UUID.randomUUID(), 10, loc(3, 0), 5, existing));
        assertEquals(1, guard.countOf(OWNER), "间距失败不得占用计数");
        // 坐标占位已回滚：同样的位置现在可以再放（minDistance=0）
        assertTrue(guard.tryReserve(OWNER, UUID.randomUUID(), 10, loc(3, 0), 0, List.of()));
    }

    @Test
    void minDistanceUsesChebyshevOnSameWorldOnly() {
        PlacementGuard guard = new PlacementGuard();
        List<BlockLocation> existing = new ArrayList<>();
        existing.add(loc(100, 100));
        // 水平切比雪夫距离 max(|dx|,|dz|)：4 < 5 → 过近
        assertFalse(guard.tryReserve(OWNER, UUID.randomUUID(), 10, loc(104, 100), 5, existing));
        // dx=5 ≥ 5 → 允许（阈值是「小于才拒绝」）
        assertTrue(guard.tryReserve(OWNER, UUID.randomUUID(), 10, loc(105, 100), 5, existing));
        // 另一世界不参与比较
        assertTrue(guard.tryReserve(OWNER, UUID.randomUUID(), 10,
                new BlockLocation("world_nether", 100, 64, 100), 5, existing));
    }

    @Test
    void releaseRestoresCountAndSlot() {
        PlacementGuard guard = new PlacementGuard();
        assertTrue(guard.tryReserve(OWNER, UUID.randomUUID(), 1, loc(0, 0), 0, List.of()));
        assertFalse(guard.tryReserve(OWNER, UUID.randomUUID(), 1, loc(50, 0), 0, List.of()), "已达上限");
        guard.release(OWNER, loc(0, 0));
        assertEquals(0, guard.countOf(OWNER));
        // 名额与坐标都已释放
        assertTrue(guard.tryReserve(OWNER, UUID.randomUUID(), 1, loc(0, 0), 0, List.of()));
    }

    @Test
    void lastReleaseRemovesOwnerEntryEntirely() {
        PlacementGuard guard = new PlacementGuard();
        guard.tryReserve(OWNER, UUID.randomUUID(), 5, loc(0, 0), 0, List.of());
        guard.tryReserve(OWNER, UUID.randomUUID(), 5, loc(10, 0), 0, List.of());
        guard.release(OWNER, loc(0, 0));
        assertEquals(1, guard.countOf(OWNER));
        guard.release(OWNER, loc(10, 0));
        assertEquals(0, guard.countOf(OWNER), "最后一个释放后不得留下 0 值条目（避免 Map 缓慢泄漏）");
    }

    @Test
    void capZeroRejectsEverything() {
        PlacementGuard guard = new PlacementGuard();
        assertFalse(guard.tryReserve(OWNER, UUID.randomUUID(), 0, loc(0, 0), 0, List.of()));
        assertEquals(0, guard.countOf(OWNER));
    }

    @Test
    void differentOwnersHaveIndependentCaps() {
        PlacementGuard guard = new PlacementGuard();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(guard.tryReserve(a, UUID.randomUUID(), 1, loc(0, 0), 0, List.of()));
        assertTrue(guard.tryReserve(b, UUID.randomUUID(), 1, loc(100, 0), 0, List.of()));
        assertFalse(guard.tryReserve(a, UUID.randomUUID(), 1, loc(200, 0), 0, List.of()), "a 已满");
        assertFalse(guard.tryReserve(b, UUID.randomUUID(), 1, loc(300, 0), 0, List.of()), "b 已满");
        assertEquals(1, guard.countOf(a));
        assertEquals(1, guard.countOf(b));
    }
}
