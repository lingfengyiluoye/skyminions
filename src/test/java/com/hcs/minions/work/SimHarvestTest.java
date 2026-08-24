package com.hcs.minions.work;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SimHarvest#plan} 模拟收获按比例分配测试（纯函数，不依赖 Bukkit 运行时）。 */
class SimHarvestTest {

    private static Map<Material, Integer> counts(Object... pairs) {
        Map<Material, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((Material) pairs[i], (Integer) pairs[i + 1]);
        }
        return m;
    }

    private static int total(List<Map.Entry<Material, Integer>> plan) {
        return plan.stream().mapToInt(Map.Entry::getValue).sum();
    }

    @Test
    void harvestNeverExceedsRequestedAmount() {
        // 3:1 占比分 4 个额度 → 总数恰好 4，不超发不少发
        List<Map.Entry<Material, Integer>> plan = SimHarvest.plan(
                counts(Material.COAL_ORE, 15, Material.IRON_ORE, 5), 4, m -> m);
        assertEquals(4, total(plan));
    }

    @Test
    void singleTypeTakesAll() {
        List<Map.Entry<Material, Integer>> plan = SimHarvest.plan(
                counts(Material.DIAMOND_ORE, 8), 3, m -> m);
        assertEquals(1, plan.size());
        assertEquals(3, plan.get(0).getValue());
    }

    @Test
    void productMappingApplied() {
        List<Map.Entry<Material, Integer>> plan = SimHarvest.plan(
                counts(Material.STONE, 10), 2,
                m -> m == Material.STONE ? Material.COBBLESTONE : m);
        assertEquals(Material.COBBLESTONE, plan.get(0).getKey());
    }

    @Test
    void emptyCountsOrZeroHarvestYieldsNothing() {
        assertTrue(SimHarvest.plan(counts(), 4, m -> m).isEmpty());
        assertTrue(SimHarvest.plan(counts(Material.WHEAT, 5), 0, m -> m).isEmpty());
    }

    @Test
    void manyTypesStillSumExactly() {
        // 四类各 1 块，收获 3：整数比例分配易丢余数，最后一类兜底补齐
        List<Map.Entry<Material, Integer>> plan = SimHarvest.plan(
                counts(Material.WHEAT, 1, Material.CARROTS, 1,
                        Material.POTATOES, 1, Material.BEETROOTS, 1), 3, m -> m);
        assertEquals(3, total(plan));
    }
}
