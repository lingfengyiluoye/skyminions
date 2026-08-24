package com.hcs.minions.model;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.Optional;

/**
 * 仆从类型枚举。类型是领域内的一等公民，工作策略通过
 * {@link com.hcs.minions.work.WorkStrategyRegistry} 按类型查表，
 * 调度器绝不 if-else 堆砌类型判断。
 */
public enum MinionType {
    MINER("miner", "矿工", Material.DIAMOND_PICKAXE, MinionCategory.MINING),
    FARMER("farmer", "农夫", Material.GOLDEN_HOE, MinionCategory.FARMING),
    LUMBERJACK("lumberjack", "伐木工", Material.IRON_AXE, MinionCategory.FORAGING),
    FISHER("fisher", "钓鱼郎", Material.FISHING_ROD, MinionCategory.FISHING),
    SLAYER("slayer", "猎魔人", Material.DIAMOND_SWORD, MinionCategory.COMBAT),
    RANCHER("rancher", "牧民", Material.SHEARS, MinionCategory.SPECIAL),
    COBBLE("cobble", "圆石匠", Material.STONE_PICKAXE, MinionCategory.MINING);

    private final String key;
    private final String displayName;
    private final Material icon;
    private final MinionCategory category;

    MinionType(String key, String displayName, Material icon, MinionCategory category) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.category = category;
    }

    public String key() {
        return key;
    }

    /** 内置中文显示名（GUI 标题兜底；实际展示优先用配置 display-name）。 */
    public String displayName() {
        return displayName;
    }

    /** 图标物料，用于 GUI 与掉落物展示。 */
    public Material icon() {
        return icon;
    }

    /** 图鉴分类（采矿/农业/伐木/战斗/钓鱼/特殊）。 */
    public MinionCategory category() {
        return category;
    }

    public static Optional<MinionType> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.key.equalsIgnoreCase(key)).findFirst();
    }
}
