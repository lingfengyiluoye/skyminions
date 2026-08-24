package com.hcs.minions.config;

import com.hcs.minions.util.ItemRef;
import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 单个仆从类型的强类型配置。
 *
 * <p>对齐 Hypixel 原版：工作范围固定为 5x5（{@code baseRadius = 2}），
 * 不随 Tier 增长。范围扩展由 {@link com.hcs.minions.upgrade.MinionUpgradeType#MINION_EXPANDER}
 * 模块以 +5% 面积形式提供。</p>
 *
 * @param product           代表性产物（离线收益用）
 * @param upgradeRecipe     升级配方（材料 -> Tier1→2 基础数量），多材料、保序（服主可自定义，
 *                          材料可为原版 Material 或 CraftEngine 自定义物品）
 * @param upgradeCostGrowth 升级数量陡增系数（每升一级 ×growth，>=1.0，越大越陡）
 * @param baseRadius      固定工作半径（2 即 5x5）
 * @param harvestCap      模拟采集单次收获上限（仅统计型策略生效：范围内可采数量越多
 *                        产量越高但不破坏方块；真实挖掘型策略忽略此值）
 * @param cooldownPerLevel 分等级动作间隔表（tick，对齐 Hypixel 原版逐级提速）：下标 = 等级-1，
 *                        超出表长的等级取最后一项；空表 = 所有等级统一用 {@code cooldownTicks}
 * @param rareDrop        专属稀有掉落（可空，对齐 Hypixel 各仆从的专属稀有掉落玩法）
 * @param rareDropChance  稀有掉落概率（0~1，0 = 关闭）
 * @param unlockAmount    收集解锁阈值：该类型产物（product）累计收集达到此量才解锁使用；
 *                        0 = 无需解锁（默认）。受全局 collection-unlock-enabled 开关控制
 */
public record MinionTypeConfig(
        String key,
        String displayName,
        int maxLevel,
        double baseEfficiency,
        double efficiencyPerLevel,
        long baseFuelTicks,
        int cooldownTicks,
        double sellPricePerUnit,
        Material product,
        Map<ItemRef, Long> upgradeRecipe,
        double upgradeCostGrowth,
        int baseRadius,
        int harvestCap,
        int[] cooldownPerLevel,
        Set<Material> targets,
        Material rareDrop,
        double rareDropChance,
        long unlockAmount
) {

    public MinionTypeConfig {
        targets = Set.copyOf(targets);
        // 配方保序且不可变（GUI 展示与扣除顺序稳定）
        upgradeRecipe = Collections.unmodifiableMap(new LinkedHashMap<>(upgradeRecipe));
        if (upgradeCostGrowth < 1.0) {
            upgradeCostGrowth = 1.0;
        }
        if (harvestCap < 1) {
            harvestCap = 1;
        }
        if (cooldownPerLevel == null) {
            cooldownPerLevel = new int[0];
        }
        if (rareDropChance < 0) {
            rareDropChance = 0;
        }
        if (unlockAmount < 0) {
            unlockAmount = 0;
        }
    }

    /** 是否配置了收集解锁门槛（还需全局开关开启才实际生效）。 */
    public boolean hasUnlockRequirement() {
        return unlockAmount > 0;
    }

    /** 是否启用了专属稀有掉落。 */
    public boolean hasRareDrop() {
        return rareDrop != null && rareDropChance > 0;
    }

    public double efficiencyAt(int level) {
        return baseEfficiency + efficiencyPerLevel * Math.max(0, level - 1);
    }

    /** 指定等级的动作间隔（tick）：配置了分等级表则查表（越界取末项），否则全等级统一 cooldownTicks。 */
    public int cooldownTicksAt(int level) {
        if (cooldownPerLevel.length == 0) {
            return cooldownTicks;
        }
        int idx = Math.min(Math.max(level, 1), cooldownPerLevel.length) - 1;
        return cooldownPerLevel[idx];
    }

    /**
     * 升到 level+1 所需配方：每种材料 = 基础数量 × growth^(level-1)，陡增曲线，至少 1。
     * 返回保序副本（供 GUI 展示与原子扣除）。
     */
    public Map<ItemRef, Long> recipeFor(int level) {
        double factor = Math.pow(upgradeCostGrowth, Math.max(0, level - 1));
        Map<ItemRef, Long> out = new LinkedHashMap<>();
        for (Map.Entry<ItemRef, Long> e : upgradeRecipe.entrySet()) {
            out.put(e.getKey(), Math.max(1L, Math.round(e.getValue() * factor)));
        }
        return out;
    }

    /** 固定工作半径（对齐 Hypixel 5x5，不随等级变）。 */
    public int radiusFor(int level) {
        return baseRadius;
    }
}
