package com.hcs.minions.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ChunkGuard} 列加载状态查询测试（纯数组逻辑，无 Bukkit 依赖）。 */
class ChunkGuardTest {

    /** 构造 (2r+1)×(2r+1) 加载矩阵：给定的相对列标已加载。 */
    private static boolean[][] matrix(int r, boolean[][] loadedRel) {
        int size = 2 * r + 1;
        boolean[][] out = new boolean[size][size];
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                out[dx + r][dz + r] = loadedRel[dx + r][dz + r];
            }
        }
        return out;
    }

    @Test
    void loadedColumnReturnsTrue() {
        boolean[][] m = matrix(1, new boolean[][]{
                {true, true, true},
                {true, true, true},
                {true, true, true},
        });
        assertTrue(ChunkGuard.isLoaded(m, 0, 0, 1));
        assertTrue(ChunkGuard.isLoaded(m, -1, 1, 1));
    }

    @Test
    void unloadedColumnReturnsFalse() {
        boolean[][] m = matrix(1, new boolean[][]{
                {true, true, true},
                {true, false, true},
                {true, true, true},
        });
        assertFalse(ChunkGuard.isLoaded(m, 0, 0, 1));
        assertTrue(ChunkGuard.isLoaded(m, -1, -1, 1));
    }

    @Test
    void outOfBoundsTreatedAsUnloaded() {
        boolean[][] m = matrix(1, new boolean[][]{
                {true, true, true},
                {true, true, true},
                {true, true, true},
        });
        // 超出半径/负向索引一律视为未加载（防止数组越界或误读邻居数据）
        assertFalse(ChunkGuard.isLoaded(m, 2, 0, 1));
        assertFalse(ChunkGuard.isLoaded(m, 0, -2, 1));
    }
}
