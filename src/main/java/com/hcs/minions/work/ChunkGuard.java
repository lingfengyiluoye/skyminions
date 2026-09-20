package com.hcs.minions.work;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 区块加载守卫：工作范围扫描前预计算「哪些水平列的区块已加载」。
 *
 * <p>Folia 下在仆从 region 线程遍历未加载/边界区块的 {@code getType()} 会触发
 * 同步区块加载或读到不一致状态。仆从区块本身已加载，但范围扩展模块把工作半径
 * 拉到 10+ 后必然跨到邻居区块（可能未加载）。本类把范围按区块去重预检一次，
 * 扫描循环里只查布尔数组，零额外开销。
 *
 * <p>纯内存坐标运算，不触碰任何 Bukkit 可变状态，任意线程可调用。</p>
 */
public final class ChunkGuard {

    private ChunkGuard() {
    }

    /**
     * 预计算以 {@code anchor} 为中心、半径 {@code r} 的水平方块是否位于已加载区块。
     *
     * @return {@code (2r+1)×(2r+1)} 布尔数组，索引 {@code [dx + r][dz + r]}
     */
    public static boolean[][] loaded(World world, Block anchor, int r) {
        int size = 2 * r + 1;
        boolean[][] out = new boolean[size][size];
        if (world == null || r < 0) {
            return out;
        }
        int ax = anchor.getX();
        int az = anchor.getZ();
        int minCx = (ax - r) >> 4;
        int maxCx = (ax + r) >> 4;
        int minCz = (az - r) >> 4;
        int maxCz = (az + r) >> 4;
        boolean[][] chunkLoaded = new boolean[maxCx - minCx + 1][maxCz - minCz + 1];
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                chunkLoaded[cx - minCx][cz - minCz] = world.isChunkLoaded(cx, cz);
            }
        }
        for (int dx = -r; dx <= r; dx++) {
            int cx = (ax + dx) >> 4;
            boolean[] row = out[dx + r];
            for (int dz = -r; dz <= r; dz++) {
                int cz = (az + dz) >> 4;
                row[dz + r] = chunkLoaded[cx - minCx][cz - minCz];
            }
        }
        return out;
    }

    /** 查询列是否已加载（越界索引视为未加载）。 */
    public static boolean isLoaded(boolean[][] columns, int dx, int dz, int r) {
        int cx = dx + r;
        int cz = dz + r;
        return cx >= 0 && cz >= 0 && cx < columns.length && cz < columns[cx].length
                && columns[cx][cz];
    }
}
