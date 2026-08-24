package com.hcs.minions.config;

import com.hcs.minions.util.ItemRef;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
                java.util.Set.of(Material.COBBLESTONE), Map.of(), null, 0.0, 0L, Map.of(), java.util.Set.of(), null);
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
                java.util.Set.of(Material.COBBLESTONE), Map.of(), null, 0.0, 0L, Map.of(), java.util.Set.of(), null);
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
                java.util.Set.of(Material.COBBLESTONE), Map.of(), null, 0.0, 0L, Map.of(), java.util.Set.of(), null);
        assertEquals(300, c.cooldownTicksAt(1));
        assertEquals(260, c.cooldownTicksAt(3));
        assertEquals(260, c.cooldownTicksAt(12)); // 超出表长取末项
        // 空表：全等级统一 cooldownTicks
        assertEquals(20, cfg(1.25).cooldownTicksAt(5));
    }

    @Test
    void tierTargetsCumulativeUnlock() {
        // 分级解锁（累加式）：基础 ∪ 所有解锁等级 ≤ 当前等级的分组
        Map<Integer, java.util.Set<Material>> tiers = new java.util.TreeMap<>();
        tiers.put(4, java.util.EnumSet.of(Material.IRON_ORE));
        tiers.put(7, java.util.EnumSet.of(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE));
        tiers.put(10, java.util.EnumSet.of(Material.DIAMOND_ORE));
        Map<ItemRef, Long> recipe = new LinkedHashMap<>();
        recipe.put(COBBLE, 64L);
        MinionTypeConfig c = new MinionTypeConfig(
                "t", "t", 12, 1.0, 0.0, 72000L, 20, 1.0,
                Material.COBBLESTONE, recipe, 1.25, 2, 1, new int[0],
                java.util.Set.of(Material.COBBLESTONE, Material.STONE), tiers,
                null, 0.0, 0L, Map.of(), java.util.Set.of(), null);

        assertTrue(c.hasTierGating());
        assertEquals(java.util.Set.of(Material.COBBLESTONE, Material.STONE), c.targetsAt(1));
        assertEquals(java.util.Set.of(Material.COBBLESTONE, Material.STONE, Material.IRON_ORE),
                c.targetsAt(4));
        var at7 = c.targetsAt(7);
        assertTrue(at7.contains(Material.GOLD_ORE) && at7.contains(Material.DEEPSLATE_GOLD_ORE)
                && at7.contains(Material.IRON_ORE));
        var at12 = c.targetsAt(12);
        assertTrue(at12.contains(Material.DIAMOND_ORE));
        assertEquals(6, at12.size());

        // 档位查询与"下一档"推进
        assertEquals(java.util.Set.of(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE), c.unlocksAt(7));
        assertEquals(java.util.Set.of(), c.unlocksAt(5));
        assertEquals(4, c.nextUnlockLevel(1));
        assertEquals(7, c.nextUnlockLevel(4));
        assertEquals(-1, c.nextUnlockLevel(10)); // 全部解锁后无下一档
    }

    @Test
    void noTierGatingKeepsBaseTargetsEverywhere() {
        MinionTypeConfig c = cfg(1.25);
        assertTrue(!c.hasTierGating());
        assertEquals(java.util.Set.of(Material.COBBLESTONE), c.targetsAt(1));
        assertSame(c.targets(), c.targetsAt(11)); // 无分级时零分配，直接返回基础集合
        assertEquals(-1, c.nextUnlockLevel(3));
    }

    @Test
    void invalidTierEntriesAreDropped() {
        // 等级 <2 或空组视为无意义档位，规整后等于未启用分级
        Map<Integer, java.util.Set<Material>> tiers = new java.util.TreeMap<>();
        tiers.put(1, java.util.EnumSet.of(Material.DIAMOND_ORE));
        tiers.put(5, java.util.Set.of());
        Map<ItemRef, Long> recipe = new LinkedHashMap<>();
        recipe.put(COBBLE, 64L);
        MinionTypeConfig c = new MinionTypeConfig(
                "t", "t", 12, 1.0, 0.0, 72000L, 20, 1.0,
                Material.COBBLESTONE, recipe, 1.25, 2, 1, new int[0],
                java.util.Set.of(Material.COBBLESTONE), tiers, null, 0.0, 0L, Map.of(), java.util.Set.of(), null);
        assertTrue(!c.hasTierGating());
    }

    @Test
    void recipeOverrideReplacesWholeRowAtExactLevel() {
        // 稀有掉落回流载体：'10' 覆盖行在该级原样生效，其他级仍走陡增基础配方
        Map<ItemRef, Long> override = new LinkedHashMap<>();
        override.put(COBBLE, 128L);
        override.put(new ItemRef.VanillaRef(Material.EMERALD), 8L);
        Map<Integer, Map<ItemRef, Long>> overrides = Map.of(10, override);
        MinionTypeConfig c = new MinionTypeConfig(
                "t", "t", 12, 1.0, 0.0, 72000L, 20, 1.0,
                Material.COBBLESTONE, new LinkedHashMap<>(Map.of(COBBLE, 64L)), 1.25, 2, 1, new int[0],
                java.util.Set.of(Material.COBBLESTONE), Map.of(), null, 0.0, 0L, overrides,
                java.util.Set.of(), null);
        // 非覆盖级：基础陡增
        assertEquals(64L, c.recipeFor(1).get(COBBLE));
        // 覆盖级：整行替换，且包含回流材料
        var at10 = c.recipeFor(10);
        assertEquals(128L, at10.get(COBBLE));
        assertEquals(8L, at10.get(new ItemRef.VanillaRef(Material.EMERALD)));
        assertTrue(!at10.containsKey(COAL));
        // 覆盖级之后（11）回到基础陡增曲线，不被污染
        assertEquals(Math.round(64 * Math.pow(1.25, 10)), c.recipeFor(11).get(COBBLE));
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
