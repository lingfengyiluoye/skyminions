package com.hcs.minions.upgrade;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.Optional;

/**
 * 仆从升级模块类型（Hypixel 原版玩法，一比一对齐）。
 *
 * <p>仆从拥有两个模块槽位，随 Tier 解锁（见 {@code Minion#unlockedUpgradeSlots}）。
 * 模块效果统一在 {@link UpgradeService} 中结算。</p>
 *
 * <ul>
 *   <li>自动熔炼 AUTO_SMELTER：矿石/沙子 -> 熔炼产物</li>
 *   <li>自动压缩 COMPACTOR：仓内散装 9:1 压成方块形态（仓储级结算）</li>
 *   <li>超级压缩 SUPER_COMPACTOR：仓内散装 160:1 压成附魔资源（存储效率核心）</li>
 *   <li>钻石散布 DIAMOND_SPREADING：每次工作概率额外产出钻石</li>
 *   <li>范围扩展 MINION_EXPANDER：工作面积 +5%（对齐原版，非半径 +1）</li>
 *   <li>自动售卖 AUTO_SELLER：仓库满时自动出售（对齐原版 Enchanted/Budget Hopper 思路）</li>
 *   <li>简易漏斗 BUDGET_HOPPER：产出即时折价售卖（50%，对齐 Hypixel Budget Hopper）</li>
 *   <li>附魔漏斗 ENCHANTED_HOPPER：产出即时高价售卖（90%，对齐 Hypixel Enchanted Hopper）</li>
 *   <li>腐化之土 CORRUPT_SOIL：每次工作有概率额外产出腐化副产物（硫磺+腐化碎片，对齐 Hypixel Corrupt Soil）</li>
 *   <li>储物箱 STORAGE_SMALL/MEDIUM/LARGE：额外解锁 +6/+12/+18 格仓库存储（对齐 Hypixel Storage 升级，占模块槽）</li>
 * </ul>
 */
public enum MinionUpgradeType {

    AUTO_SMELTER("auto_smelter", "自动熔炼", Material.FURNACE, "矿石与沙子自动熔炼"),
    COMPACTOR("compactor", "自动压缩", Material.CRAFTING_TABLE, "仓内散装资源自动 9:1 压成方块"),
    SUPER_COMPACTOR("super_compactor", "超级压缩 3000", Material.ENCHANTED_GOLDEN_APPLE, "仓内散装资源自动 160:1 压成附魔资源"),
    DIAMOND_SPREADING("diamond_spreading", "钻石散布", Material.DIAMOND, "工作时有概率额外产出钻石"),
    MINION_EXPANDER("minion_expander", "范围扩展", Material.STONE_BRICKS, "扩大工作范围（面积放大）"),
    AUTO_SELLER("auto_seller", "自动售卖漏斗", Material.HOPPER, "仓库满时自动出售产物"),
    BUDGET_HOPPER("budget_hopper", "简易漏斗", Material.HOPPER, "产出即时售卖（价格 50%，无需等满仓）"),
    ENCHANTED_HOPPER("enchanted_hopper", "附魔漏斗", Material.HOPPER_MINECART, "产出即时售卖（价格 90%，无需等满仓）"),
    CORRUPT_SOIL("corrupt_soil", "腐化之土", Material.SCULK, "工作时有概率额外产出硫磺与腐化碎片"),
    STORAGE_SMALL("storage_small", "小型储物箱", Material.CHEST, "额外解锁 +6 格仓库存储（占用一个模块槽）"),
    STORAGE_MEDIUM("storage_medium", "中型储物箱", Material.TRAPPED_CHEST, "额外解锁 +12 格仓库存储（占用一个模块槽）"),
    STORAGE_LARGE("storage_large", "大型储物箱", Material.ENDER_CHEST, "额外解锁 +18 格仓库存储（占用一个模块槽）");

    /** 储物箱模块提供的额外存储格数（0 = 非储物箱模块）。 */
    public int bonusStorageSlots() {
        return switch (this) {
            case STORAGE_SMALL -> 6;
            case STORAGE_MEDIUM -> 12;
            case STORAGE_LARGE -> 18;
            default -> 0;
        };
    }

    private final String key;
    private final String displayName;
    private final Material icon;
    private final String description;

    MinionUpgradeType(String key, String displayName, Material icon, String description) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    /** 模块图标物料，用于物品与 GUI 展示。 */
    public Material icon() {
        return icon;
    }

    public String description() {
        return description;
    }

    public static Optional<MinionUpgradeType> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.key.equalsIgnoreCase(key)).findFirst();
    }
}
