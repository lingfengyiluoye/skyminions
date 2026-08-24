package com.hcs.minions.work;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 模拟收获分配工具（统计型策略共用）。
 *
 * <p>统计型策略只统计范围内可采集方块的数量（纯内存读取，不破坏方块、
 * 无光照/物理/方块更新包开销），产量与数量成正比：
 * 单次收获 = min(可采数量, harvest-cap)，再按各方块占比分配产出。</p>
 */
public final class SimHarvest {

    private SimHarvest() {
    }

    /**
     * 纯函数：将 harvest 个收获额度按各方可采方块的数量占比分配为 (产物, 数量) 计划。
     * 不触碰 Bukkit 运行时（可单测）。
     *
     * @param counts     各可采方块类型 -> 数量（保序）
     * @param harvest    本次总收获量（已按 harvest-cap 截断）
     * @param productOf  方块 -> 产物映射（未收录的类型原样产出）
     */
    public static List<Map.Entry<Material, Integer>> plan(Map<Material, Integer> counts, int harvest,
                                                          Function<Material, Material> productOf) {
        List<Map.Entry<Material, Integer>> out = new ArrayList<>();
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0 || harvest <= 0) {
            return out;
        }
        int remaining = harvest;
        List<Map.Entry<Material, Integer>> entries = new ArrayList<>(counts.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Material, Integer> e = entries.get(i);
            // 最后一类兜底吃掉余数，保证总产出恰好等于 harvest
            int take = (i == entries.size() - 1)
                    ? remaining
                    : (int) ((long) harvest * e.getValue() / total);
            take = Math.min(take, remaining);
            if (take > 0) {
                out.add(Map.entry(productOf.apply(e.getKey()), take));
                remaining -= take;
            }
        }
        return out;
    }

    /** 按计划构建产物堆（仅运行时调用）。 */
    public static List<ItemStack> allocate(Map<Material, Integer> counts, int harvest,
                                           Function<Material, Material> productOf) {
        List<Map.Entry<Material, Integer>> p = plan(counts, harvest, productOf);
        List<ItemStack> drops = new ArrayList<>(p.size());
        for (Map.Entry<Material, Integer> e : p) {
            drops.add(new ItemStack(e.getKey(), e.getValue()));
        }
        return drops;
    }
}
