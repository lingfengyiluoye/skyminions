package com.hcs.minions.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BlockSearcher#generateOffsets(int)} 纯逻辑测试。 */
class BlockSearcherTest {

    @Test
    void offsetsCountMatchesVolumeMinusOrigin() {
        for (int r = 1; r <= 5; r++) {
            List<int[]> offsets = BlockSearcher.generateOffsets(r);
            assertEquals((2 * r + 1) * (2 * r + 1) * (2 * r + 1) - 1, offsets.size(),
                    "半径 " + r + " 的偏移数量应为体积减原点");
        }
    }

    @Test
    void offsetsExcludeOrigin() {
        for (int r = 1; r <= 3; r++) {
            for (int[] o : BlockSearcher.generateOffsets(r)) {
                assertFalse(o[0] == 0 && o[1] == 0 && o[2] == 0, "偏移列表不应包含原点");
            }
        }
    }

    @Test
    void offsetsSortedByDistance() {
        List<int[]> offsets = BlockSearcher.generateOffsets(3);
        for (int i = 1; i < offsets.size(); i++) {
            int[] a = offsets.get(i - 1);
            int[] b = offsets.get(i);
            int da = a[0] * a[0] + a[1] * a[1] + a[2] * a[2];
            int db = b[0] * b[0] + b[1] * b[1] + b[2] * b[2];
            assertTrue(da <= db, "应按到原点的距离递增排序");
        }
    }
}
