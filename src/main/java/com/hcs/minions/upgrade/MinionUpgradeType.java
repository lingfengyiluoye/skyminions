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
 *   <li>自动压缩 COMPACTOR：9:1 合成方块形态</li>
 *   <li>超级压缩 SUPER_COMPACTOR：散装 -> 附魔形态（9:1 再 9:1，存储效率核心）</li>
 *   <li>钻石散布 DIAMOND_SPREADING：每次工作概率额外产出钻石</li>
 *   <li>范围扩展 MINION_EXPANDER：工作面积 +5%（对齐原版，非半径 +1）</li>
 *   <li>自动售卖 AUTO_SELLER：仓库满时自动出售（对齐原版 Enchanted/Budget Hopper 思路）</li>
 * </ul>
 */
public enum MinionUpgradeType {

    AUTO_SMELTER("auto_smelter", "自动熔炼", Material.FURNACE, "矿石与沙子自动熔炼"),
    COMPACTOR("compactor", "自动压缩", Material.CRAFTING_TABLE, "9:1 合成方块形态"),
    SUPER_COMPACTOR("super_compactor", "超级压缩 3000", Material.ENCHANTED_GOLDEN_APPLE, "散装资源自动合成为附魔形态"),
    DIAMOND_SPREADING("diamond_spreading", "钻石散布", Material.DIAMOND, "工作时有概率额外产出钻石"),
    MINION_EXPANDER("minion_expander", "范围扩展", Material.STONE_BRICKS, "工作面积 +5%"),
    AUTO_SELLER("auto_seller", "自动售卖漏斗", Material.HOPPER, "仓库满时自动出售产物");

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
