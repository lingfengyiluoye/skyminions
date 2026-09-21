package com.hcs.minions.service;

import com.hcs.minions.model.MinionData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SnapshotBatcher} 回归测试——守住「迟到的快照必须单独补刷」这条不变量。
 *
 * <p>这条规则对应历史上的 P0-2：批次超时结算后，region 回调才生成快照。
 * 若把它并进已提交批次，那批已经落库 → 丢数据；若悄悄丢弃 → 也丢数据。
 * 唯一正确行为是拒绝并入、由调用方单独补刷。</p>
 */
class SnapshotBatcherTest {

    /** 构造一份只用于计数的快照（record 无需 Bukkit）。 */
    private static MinionData snapshot(UUID id) {
        return new MinionData(id, UUID.randomUUID(), "cobble", 1,
                "world", 0, 64, 0, 0L, 1.0, 1.0, 0L, 0L, 0L, 0L,
                null, null, null, null, null, null, false, 0L, 1.0, new byte[0]);
    }

    @Test
    void offersBeforeSettleAllLandInBatch() {
        SnapshotBatcher batch = new SnapshotBatcher();
        List<MinionData> offered = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MinionData d = snapshot(UUID.randomUUID());
            offered.add(d);
            assertTrue(batch.offer(d), "结算前的 offer 必须被接受");
        }
        assertEquals(5, batch.pending());
        List<MinionData> settled = batch.settle();
        assertEquals(5, settled.size());
        assertTrue(settled.containsAll(offered), "结算列表必须包含全部已收快照");
        assertEquals(0, batch.pending(), "结算后批次必须清空");
    }

    @Test
    void offersAfterSettleAreRejectedSoCallerFlushesSeparately() {
        SnapshotBatcher batch = new SnapshotBatcher();
        batch.offer(snapshot(UUID.randomUUID()));
        List<MinionData> first = batch.settle();
        assertEquals(1, first.size());

        // 迟到的快照：必须被拒绝，逼调用方走单独补刷路径
        MinionData late = snapshot(UUID.randomUUID());
        assertFalse(batch.offer(late), "批次已结算后必须拒绝新快照");
        assertTrue(batch.settle().isEmpty(), "重复结算必须返回空（幂等）");
        assertFalse(batch.offer(late), "拒绝状态不会因重复结算而改变");
    }

    @Test
    void nullSnapshotIsRejected() {
        SnapshotBatcher batch = new SnapshotBatcher();
        assertFalse(batch.offer(null));
        assertEquals(0, batch.pending());
    }

    @Test
    void concurrentOffersAreNeitherLostNorDuplicated() throws Exception {
        SnapshotBatcher batch = new SnapshotBatcher();
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<MinionData> all = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        MinionData d = snapshot(UUID.randomUUID());
                        all.add(d);
                        batch.offer(d);
                    }
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

        List<MinionData> settled = batch.settle();
        assertEquals(threads * perThread, all.size(), "测试自身构造的快照数");
        assertEquals(all.size(), settled.size(), "并发 offer 一份都不能丢");
        assertEquals(all.size(), new java.util.HashSet<>(settled).size(), "也不能重复计入");
    }

    @Test
    void settleIsAtomicAgainstConcurrentOffers() throws Exception {
        // 一边 settle 一边 offer：最终要么进批次（仅当 settle 尚未发生），要么被拒。
        // 绝不出现「加了却不在任何批次里」的孤儿。
        SnapshotBatcher batch = new SnapshotBatcher();
        int n = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<MinionData> accepted = java.util.Collections.synchronizedList(new ArrayList<>());
        List<MinionData> rejected = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            MinionData d = snapshot(UUID.randomUUID());
            pool.submit(() -> {
                try {
                    start.await();
                    if (batch.offer(d)) {
                        accepted.add(d);
                    } else {
                        rejected.add(d);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        // 让 offer 们跑一会儿再结算，制造交错
        Thread.sleep(20);
        List<MinionData> settled = batch.settle();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(accepted.size(), settled.size(), "被接受的必须恰好等于结算所得");
        assertEquals(n, accepted.size() + rejected.size(), "每个快照有且只有一个归属");
    }
}
