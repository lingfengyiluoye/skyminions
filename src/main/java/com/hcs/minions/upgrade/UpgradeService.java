package com.hcs.minions.upgrade;

import com.hcs.minions.model.Minion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 升级模块服务：模块物品工厂（PDC 携带类型）+ 模块效果结算。
 *
 * <p>效果处理链顺序对齐 Hypixel：自动熔炼 -> 自动压缩 -> 超级压缩 -> 钻石散布。
 * 所有映射仅做"类型替换 + 数量换算"，绝不修改入参 ItemStack（掉落物在
 * {@code MinionManager} 入仓前会被深拷贝），杜绝引用泄漏。</p>
 *
 * <p>范围扩展：+5% 面积（对齐原版）。面积为 (2r+1)^2，扩 5% 后反推半径，
 * 采用"扩大的搜索半径 = ceil(sqrt(面积 * 1.05) 的边长 / 2)"的方式近似。</p>
 */
public final class UpgradeService {

    private final NamespacedKey upgradeKey;

    /** 自动熔炼映射：矿石/沙子 -> 熔炼产物。 */
    private static final Map<Material, Material> SMELT_MAP = Map.ofEntries(
            Map.entry(Material.COAL_ORE, Material.COAL),
            Map.entry(Material.DEEPSLATE_COAL_ORE, Material.COAL),
            Map.entry(Material.IRON_ORE, Material.IRON_INGOT),
            Map.entry(Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT),
            Map.entry(Material.COPPER_ORE, Material.COPPER_INGOT),
            Map.entry(Material.DEEPSLATE_COPPER_ORE, Material.COPPER_INGOT),
            Map.entry(Material.GOLD_ORE, Material.GOLD_INGOT),
            Map.entry(Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT),
            Map.entry(Material.DIAMOND_ORE, Material.DIAMOND),
            Map.entry(Material.DEEPSLATE_DIAMOND_ORE, Material.DIAMOND),
            Map.entry(Material.EMERALD_ORE, Material.EMERALD),
            Map.entry(Material.DEEPSLATE_EMERALD_ORE, Material.EMERALD),
            Map.entry(Material.REDSTONE_ORE, Material.REDSTONE),
            Map.entry(Material.DEEPSLATE_REDSTONE_ORE, Material.REDSTONE),
            Map.entry(Material.LAPIS_ORE, Material.LAPIS_LAZULI),
            Map.entry(Material.DEEPSLATE_LAPIS_ORE, Material.LAPIS_LAZULI),
            Map.entry(Material.NETHER_GOLD_ORE, Material.GOLD_NUGGET),
            Map.entry(Material.SAND, Material.GLASS),
            Map.entry(Material.RED_SAND, Material.GLASS)
    );

    /** 自动压缩映射：散装资源 -> 9:1 方块形态。 */
    private static final Map<Material, Material> COMPACT_MAP = Map.ofEntries(
            Map.entry(Material.COAL, Material.COAL_BLOCK),
            Map.entry(Material.IRON_INGOT, Material.IRON_BLOCK),
            Map.entry(Material.COPPER_INGOT, Material.COPPER_BLOCK),
            Map.entry(Material.GOLD_INGOT, Material.GOLD_BLOCK),
            Map.entry(Material.DIAMOND, Material.DIAMOND_BLOCK),
            Map.entry(Material.EMERALD, Material.EMERALD_BLOCK),
            Map.entry(Material.REDSTONE, Material.REDSTONE_BLOCK),
            Map.entry(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK),
            Map.entry(Material.RAW_IRON, Material.RAW_IRON_BLOCK),
            Map.entry(Material.RAW_GOLD, Material.RAW_GOLD_BLOCK),
            Map.entry(Material.RAW_COPPER, Material.RAW_COPPER_BLOCK),
            Map.entry(Material.WHEAT, Material.HAY_BLOCK),
            Map.entry(Material.MELON_SLICE, Material.MELON),
            Map.entry(Material.SNOWBALL, Material.SNOW_BLOCK),
            Map.entry(Material.SLIME_BALL, Material.SLIME_BLOCK),
            Map.entry(Material.CLAY_BALL, Material.CLAY),
            Map.entry(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK),
            Map.entry(Material.IRON_NUGGET, Material.IRON_INGOT),
            Map.entry(Material.GOLD_NUGGET, Material.GOLD_INGOT)
    );

    /** 超级压缩映射：散装资源 -> 附魔形态（9:1 再 9:1，用方块作附魔形态占位）。 */
    private static final Map<Material, Material> SUPER_COMPACT_MAP = Map.ofEntries(
            Map.entry(Material.COAL, Material.COAL_BLOCK),
            Map.entry(Material.IRON_INGOT, Material.IRON_BLOCK),
            Map.entry(Material.COPPER_INGOT, Material.COPPER_BLOCK),
            Map.entry(Material.GOLD_INGOT, Material.GOLD_BLOCK),
            Map.entry(Material.DIAMOND, Material.DIAMOND_BLOCK),
            Map.entry(Material.EMERALD, Material.EMERALD_BLOCK),
            Map.entry(Material.REDSTONE, Material.REDSTONE_BLOCK),
            Map.entry(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK),
            Map.entry(Material.WHEAT, Material.HAY_BLOCK),
            Map.entry(Material.MELON_SLICE, Material.MELON),
            Map.entry(Material.SNOWBALL, Material.SNOW_BLOCK),
            Map.entry(Material.SLIME_BALL, Material.SLIME_BLOCK),
            Map.entry(Material.CLAY_BALL, Material.CLAY)
    );

    /** 超级压缩比例：散装 -> 附魔形态（1 个附魔 = 160 个散装的压缩，简化为 81:1）。 */
    private static final int SUPER_COMPACT_RATIO = 81;

    /** 钻石散布：每次工作额外产出钻石的概率。 */
    private static final double DIAMOND_SPREAD_CHANCE = 0.10;

    /** 范围扩展：面积增幅比例。 */
    private static final double EXPANDER_AREA_BONUS = 1.05;

    public UpgradeService(JavaPlugin plugin) {
        this.upgradeKey = new NamespacedKey(plugin, "minion_upgrade");
    }

    // ------------------------------------------------------------------
    // 模块物品工厂
    // ------------------------------------------------------------------

    /** 生成一个模块物品（PDC 携带模块类型，跨重启安全）。 */
    public ItemStack createItem(MinionUpgradeType type) {
        ItemStack item = new ItemStack(type.icon());
        ItemMeta meta = item.getItemMeta();
        // 去斜体：Paper 客户端对未显式设置 ITALIC 的物品名/Lore 按原版默认斜体渲染
        meta.displayName(Component.text(type.displayName(), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(type.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("手持点击仆从的模块槽装备", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(upgradeKey, PersistentDataType.STRING, type.key());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isUpgrade(ItemStack item) {
        return parseType(item).isPresent();
    }

    public Optional<MinionUpgradeType> parseType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String key = item.getItemMeta().getPersistentDataContainer().get(upgradeKey, PersistentDataType.STRING);
        return MinionUpgradeType.fromKey(key);
    }

    // ------------------------------------------------------------------
    // 模块效果结算
    // ------------------------------------------------------------------

    /**
     * 范围扩展：装备模块时工作面积 +5%（对齐 Hypixel 原版，非半径 +1）。
     * 面积 (2r+1)^2 放大 1.05 后反推边长与半径。
     */
    public int radiusFor(Minion minion, int baseRadius) {
        if (!minion.hasUpgrade(MinionUpgradeType.MINION_EXPANDER)) {
            return baseRadius;
        }
        return radiusForArea(baseRadius, EXPANDER_AREA_BONUS);
    }

    /** 纯函数：面积放大 {@code areaBonus} 后反推工作半径（保持奇数边长对称），供单测验证。 */
    static int radiusForArea(int baseRadius, double areaBonus) {
        int side = 2 * baseRadius + 1;
        double expandedSide = side * Math.sqrt(areaBonus);
        int newSide = (int) Math.ceil(expandedSide);
        if (newSide % 2 == 0) {
            newSide++; // 保持奇数，保证对称
        }
        return (newSide - 1) / 2;
    }

    /** 是否装备了自动售卖模块。 */
    public boolean hasAutoSell(Minion minion) {
        return minion.hasUpgrade(MinionUpgradeType.AUTO_SELLER);
    }

    /**
     * 掉落物处理链：自动熔炼 -> 自动压缩 -> 超级压缩 -> 钻石散布。
     * 返回全新列表，不修改入参 ItemStack。
     */
    public List<ItemStack> processDrops(Minion minion, List<ItemStack> drops, Random random) {
        List<ItemStack> result = new ArrayList<>(drops);
        if (minion.hasUpgrade(MinionUpgradeType.AUTO_SMELTER)) {
            result = smelt(result);
        }
        if (minion.hasUpgrade(MinionUpgradeType.SUPER_COMPACTOR)) {
            result = superCompact(result);
        } else if (minion.hasUpgrade(MinionUpgradeType.COMPACTOR)) {
            result = compact(result);
        }
        if (minion.hasUpgrade(MinionUpgradeType.DIAMOND_SPREADING) && random.nextDouble() < DIAMOND_SPREAD_CHANCE) {
            result.add(new ItemStack(Material.DIAMOND, 1));
        }
        return result;
    }

    private List<ItemStack> smelt(List<ItemStack> drops) {
        List<ItemStack> out = new ArrayList<>(drops.size());
        for (ItemStack item : drops) {
            Material smelted = SMELT_MAP.get(item.getType());
            out.add(smelted == null ? item : new ItemStack(smelted, item.getAmount()));
        }
        return out;
    }

    private List<ItemStack> compact(List<ItemStack> drops) {
        return compress(drops, COMPACT_MAP, 9);
    }

    private List<ItemStack> superCompact(List<ItemStack> drops) {
        return compress(drops, SUPER_COMPACT_MAP, SUPER_COMPACT_RATIO);
    }

    private List<ItemStack> compress(List<ItemStack> drops, Map<Material, Material> map, int ratio) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : drops) {
            Material target = map.get(item.getType());
            int amount = item.getAmount();
            if (target == null || amount < ratio) {
                out.add(item);
                continue;
            }
            long[] pair = compressCount(amount, ratio);
            int compressed = (int) pair[0];
            int remainder = (int) pair[1];
            if (compressed > 0) {
                out.add(new ItemStack(target, compressed));
            }
            if (remainder > 0) {
                out.add(new ItemStack(item.getType(), remainder));
            }
        }
        return out;
    }

    /** 纯函数：压缩换算，返回 [压缩数量, 余数]，供单测验证。 */
    static long[] compressCount(long amount, int ratio) {
        return new long[]{amount / ratio, amount % ratio};
    }
}
