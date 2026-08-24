package com.hcs.minions.service;

import com.hcs.minions.model.Minion;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 限流方块搜索器（两阶段螺旋扫描）。
 *
 * <p>阶段一：每个周期都检查最近的 {@code maxChecks/2} 个偏移 —— 骨粉催熟的作物、
 * 旁边的树等"新出现"的近处目标能立刻被命中，不用等一整圈。</p>
 *
 * <p>阶段二：游标在剩余偏移中推进，保证远处目标最终也被扫到。单周期总检查数 &lt;50。</p>
 */
public final class BlockSearcher {

    public static final int HARD_LIMIT = 48;

    private static final ConcurrentHashMap<Integer, List<int[]>> OFFSETS = new ConcurrentHashMap<>();

    private final int maxChecks;

    public BlockSearcher(int maxChecks) {
        this.maxChecks = Math.min(Math.max(1, maxChecks), HARD_LIMIT);
    }

    public Optional<Block> find(World world, Block anchor, int radius, Predicate<Block> target, Minion minion) {
        List<int[]> offsets = offsets(radius);
        int n = offsets.size();
        int near = Math.min(n, maxChecks / 2);

        // 阶段一：近处每周期必查
        for (int i = 0; i < near; i++) {
            int[] o = offsets.get(i);
            Block b = anchor.getRelative(o[0], o[1], o[2]);
            if (b.getChunk().isLoaded() && target.test(b)) {
                return Optional.of(b);
            }
        }

        // 阶段二：远处游标推进
        if (n > near) {
            int farCount = n - near;
            int start = Math.floorMod(minion.scanCursor(), farCount);
            int budget = maxChecks - near;
            int checked = 0;
            while (checked < budget) {
                int[] o = offsets.get(near + (start + checked) % farCount);
                Block b = anchor.getRelative(o[0], o[1], o[2]);
                checked++;
                if (b.getChunk().isLoaded() && target.test(b)) {
                    minion.setScanCursor((start + checked) % farCount);
                    return Optional.of(b);
                }
            }
            minion.setScanCursor((start + checked) % farCount);
        }
        return Optional.empty();
    }

    private List<int[]> offsets(int radius) {
        return OFFSETS.computeIfAbsent(radius, BlockSearcher::generateOffsets);
    }

    /** 纯函数：生成半径 r 的螺旋偏移列表（按距离排序、排除原点），供单测验证。 */
    static List<int[]> generateOffsets(int r) {
        List<int[]> out = new ArrayList<>((2 * r + 1) * (2 * r + 1) * (2 * r + 1));
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    out.add(new int[]{dx, dy, dz});
                }
            }
        }
        out.sort(Comparator
                .comparingInt((int[] o) -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
                .thenComparingInt(o -> o[0])
                .thenComparingInt(o -> o[1])
                .thenComparingInt(o -> o[2]));
        return out;
    }
}
