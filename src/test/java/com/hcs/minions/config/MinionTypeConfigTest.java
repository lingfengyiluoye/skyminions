package com.hcs.minions.config;

import com.hcs.minions.util.ItemRef;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MinionTypeConfig#recipeFor(int)} 升级配方陡增曲线测试。 */
class MinionTypeConfigTest {

    private static final ItemRef COBBLE = new ItemRef.VanillaRef(Material.COBBLESTONE);
    private static final ItemRef COAL = new ItemRef.VanillaRef(Material.COAL);

    private static MinionTypeConfig cfg(double growth) {
        Map<ItemRef, Long> recipe = new LinkedHashMap<>();
        recipe.put(COBBLE, 64L);
        recipe.put(COAL, 16L);
        return new MinionTypeConfig(
                "test", "测试", 12, 1.0, 0.1, 72000L, 20, 1.0,
                Material.COBBLESTONE, recipe, growth, 2, 1, new int[0],
                java.util.Set.of(Material.COBBLESTONE), null, 0.0, 0L);
    }

    @Test
    void levelOneUsesBaseAmounts() {
        Map<ItemRef, Long> recipe = cfg(1.25).recipeFor(1);
        assertEquals(64L, recipe.get(COBBLE));
        assertEquals(16L, recipe.get(COAL));
    }

    @Test
    void higherLevelGrowsExponentially() {
        // growth 1.25：level 3 → ×1.25^2 = 1.5625
        Map<ItemRef, Long> recipe = cfg(1.25).recipeFor(3);
        assertEquals(100L, recipe.get(COBBLE)); // round(64 × 1.5625)
        assertEquals(25L, recipe.get(COAL));    // round(16 × 1.5625)
    }

    @Test
    void growthBelowOneClampsToOne() {
        // growth < 1.0 被钳制为 1.0（不允许越升越便宜）
        Map<ItemRef, Long> recipe = cfg(0.5).recipeFor(5);
        assertEquals(64L, recipe.get(COBBLE));
        assertEquals(16L, recipe.get(COAL));
    }

    @Test
    void minimumOneForTinyRecipes() {
        Map<ItemRef, Long> recipe = new LinkedHashMap<>();
        recipe.put(new ItemRef.VanillaRef(Material.APPLE), 1L);
        MinionTypeConfig c = new MinionTypeConfig(
                "t", "t", 12, 1.0, 0.1, 72000L, 20, 1.0,
                Material.COBBLESTONE, recipe, 1.01, 2, 1, new int[0],
                java.util.Set.of(Material.COBBLESTONE), null, 0.0, 0L);
        // round(1 × 1.01^n) 可能为 1 附近，至少保底 1
        assertTrue(c.recipeFor(1).get(new ItemRef.VanillaRef(Material.APPLE)) >= 1);
        assertTrue(c.recipeFor(11).get(new ItemRef.VanillaRef(Material.APPLE)) >= 1);
    }

    @Test
    void recipeOrderPreserved() {
        // 保序：GUI 展示与扣除顺序稳定
        List<ItemRef> order = cfg(1.25).recipeFor(1).keySet().stream().toList();
        assertEquals(List.of(COBBLE, COAL), order);
    }

    @Test
    void recipeIsImmutable() {
        Map<ItemRef, Long> recipe = cfg(1.25).upgradeRecipe();
        assertEquals(true, isImmutable(recipe));
    }

    @Test
    void cooldownTableLookupByLevel() {
        // 逐级表（对齐 Hypixel）：下标 = 等级-1，越界取末项；空表回退统一值
        Map<ItemRef, Long> recipe = new LinkedHashMap<>();
        recipe.put(COBBLE, 64L);
        MinionTypeConfig c = new MinionTypeConfig(
                "t", "t", 12, 1.0, 0.0, 72000L, 300, 1.0,
                Material.COBBLESTONE, recipe, 1.25, 2, 1, new int[]{300, 300, 260},
                java.util.Set.of(Material.COBBLESTONE), null, 0.0, 0L);
        assertEquals(300, c.cooldownTicksAt(1));
        assertEquals(260, c.cooldownTicksAt(3));
        assertEquals(260, c.cooldownTicksAt(12)); // 超出表长取末项
        // 空表：全等级统一 cooldownTicks
        assertEquals(20, cfg(1.25).cooldownTicksAt(5));
    }

    private static boolean isImmutable(Map<ItemRef, Long> map) {
        try {
            map.put(new ItemRef.VanillaRef(Material.DIRT), 1L);
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }
}
