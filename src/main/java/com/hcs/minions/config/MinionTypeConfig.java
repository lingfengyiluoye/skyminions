package com.hcs.minions.config;

import com.hcs.minions.util.ItemRef;
import org.bukkit.Material;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
 * @param targets         基础目标方块（等级 1 起即可采集）
 * @param targetsByTier   分级解锁目标（累加式）：键 = 解锁等级，值 = 该级新增的可采目标；
 *                        空 = 不做分级限制。对齐 Hypixel「升级解锁高价值资源」玩法，
 *                        低 Tier 仆从摆高价值方块不产出
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
        Map<Integer, Set<Material>> targetsByTier,
        Material rareDrop,
        double rareDropChance,
        long unlockAmount,
        /** 指定等级的配方整行覆盖（稀有掉落回流载体）：键 = 升级前等级，值 = 该级完整配方。 */
        Map<Integer, Map<ItemRef, Long>> recipeOverrides,
        /** 战斗型定向目标（实体类型名集合，空 = 任意敌对）。 */
        java.util.Set<String> preferredTargets,
        /** 畜牧型指定畜种（实体类型名，null = 从池中随机）。 */
        String ranchAnimalKey
) {

    public MinionTypeConfig {
        targets = Set.copyOf(targets);
        targetsByTier = normalizeTiers(targetsByTier);
        recipeOverrides = normalizeOverrides(recipeOverrides);
        preferredTargets = preferredTargets == null ? Set.of()
                : Set.copyOf(preferredTargets.stream().filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toUpperCase(java.util.Locale.ROOT)).toList());
        ranchAnimalKey = ranchAnimalKey == null || ranchAnimalKey.isBlank()
                ? null : ranchAnimalKey.trim().toUpperCase(java.util.Locale.ROOT);
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

    /** 是否配置了专属稀有掉落。 */
    public boolean hasRareDrop() {
        return rareDrop != null && rareDropChance > 0;
    }

    /** 是否启用了分级解锁目标（targets-by-tier 非空）。 */
    public boolean hasTierGating() {
        return !targetsByTier.isEmpty();
    }

    /**
     * 指定等级可用的目标集合：基础 targets ∪ 所有解锁等级 ≤ level 的分组（累加式）。
     * 每仆从每个工作周期调用一次，临时 EnumSet 开销可忽略。
     */
    public Set<Material> targetsAt(int level) {
        if (targetsByTier.isEmpty()) {
            return targets;
        }
        EnumSet<Material> out = EnumSet.noneOf(Material.class);
        out.addAll(targets);
        for (Map.Entry<Integer, Set<Material>> e : targetsByTier.entrySet()) {
            if (e.getKey() <= level) {
                out.addAll(e.getValue());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** 下一个尚未解锁的目标档位等级（无则 -1）：GUI 信息卡展示用（TreeMap 升序遍历）。 */
    public int nextUnlockLevel(int level) {
        for (Integer tier : targetsByTier.keySet()) {
            if (tier > level) {
                return tier;
            }
        }
        return -1;
    }

    /** 达到 tierLevel 时新增解锁的目标组（无该档返回空集）。 */
    public Set<Material> unlocksAt(int tierLevel) {
        return targetsByTier.getOrDefault(tierLevel, Set.of());
    }

    /** 规整分级解锁表：按等级升序、剔除无效档位（&lt;2 级或空组），产出不可变快照。 */
    private static Map<Integer, Set<Material>> normalizeTiers(Map<Integer, Set<Material>> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Set<Material>> sorted = new TreeMap<>();
        for (Map.Entry<Integer, Set<Material>> e : in.entrySet()) {
            Integer tier = e.getKey();
            if (tier == null || tier < 2 || e.getValue() == null || e.getValue().isEmpty()) {
                continue; // 1 级即基础 targets，该档位无意义
            }
            sorted.put(tier, Set.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(sorted);
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
     * 升到 level+1 所需配方：若配置了该级的整行覆盖（{@code upgrade-recipe-at}）
     * 则原样返回覆盖行（稀有掉落回流载体）；否则按 基础数量 × growth^(level-1) 陡增曲线，
     * 至少 1。返回保序副本（供 GUI 展示与原子扣除）。
     */
    public Map<ItemRef, Long> recipeFor(int level) {
        Map<ItemRef, Long> override = recipeOverrides.get(level);
        if (override != null) {
            return new LinkedHashMap<>(override);
        }
        double factor = Math.pow(upgradeCostGrowth, Math.max(0, level - 1));
        Map<ItemRef, Long> out = new LinkedHashMap<>();
        for (Map.Entry<ItemRef, Long> e : upgradeRecipe.entrySet()) {
            out.put(e.getKey(), Math.max(1L, Math.round(e.getValue() * factor)));
        }
        return out;
    }

    /** 规整分阶配方覆盖：剔除空/无效档位，产出不可变快照。 */
    private static Map<Integer, Map<ItemRef, Long>> normalizeOverrides(Map<Integer, Map<ItemRef, Long>> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Map<ItemRef, Long>> out = new TreeMap<>();
        for (Map.Entry<Integer, Map<ItemRef, Long>> e : in.entrySet()) {
            Integer level = e.getKey();
            if (level == null || level < 1 || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            out.put(level, Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 固定工作半径（对齐 Hypixel 5x5，不随等级变）。 */
    public int radiusFor(int level) {
        return baseRadius;
    }

    /** 该实体是否属于本类型的定向猎杀目标（未配置偏好时恒 false，走通用匹配）。 */
    public boolean isPreferred(org.bukkit.entity.EntityType type) {
        return preferredTargets.contains(type.name());
    }

    /** 畜牧型指定的畜种（解析失败/未配置返回 null）。 */
    public org.bukkit.entity.EntityType ranchAnimal() {
        if (ranchAnimalKey == null) {
            return null;
        }
        try {
            return org.bukkit.entity.EntityType.valueOf(ranchAnimalKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
