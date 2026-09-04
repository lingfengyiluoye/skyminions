package com.hcs.minions.upgrade;

import com.hcs.minions.model.Minion;
import com.hcs.minions.util.EnchantedResource;
import com.hcs.minions.util.ItemRef;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 升级模块服务：模块物品工厂（PDC 携带类型）+ 模块效果结算。
 *
 * <p>效果处理链对齐 Hypixel：自动熔炼/钻石散布/腐化之土在掉落时结算；
 * 自动压缩/超级压缩走<b>仓储级结算</b>（{@link #compactStorage}）——单次掉落只有
 * 几个散装，按掉落堆判定永远凑不够压缩比例，必须按全仓总量聚合换算。
 * 所有映射仅做"类型替换 + 数量换算"，绝不修改入参 ItemStack（掉落物在
 * {@code MinionManager} 入仓前会被深拷贝），杜绝引用泄漏。</p>
 *
 * <p>范围扩展：+5% 面积（对齐原版）。面积为 (2r+1)^2，扩 5% 后反推半径，
 * 采用"扩大的搜索半径 = ceil(sqrt(面积 * 1.05) 的边长 / 2)"的方式近似。</p>
 */
public final class UpgradeService {

    private final NamespacedKey upgradeKey;

    /** 自动熔炼映射：矿石/原矿/沙子 -> 熔炼产物。 */
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
            // 模拟采集的产物是「原矿」形态（RAW_*），不是矿石方块：
            // 不补这三行，自动熔炼对铁/铜/金仆从永远不生效，且附魔铁锭/铜锭/金锭
            //（以锭形态为基底）也永远无法得出
            Map.entry(Material.RAW_IRON, Material.IRON_INGOT),
            Map.entry(Material.RAW_COPPER, Material.COPPER_INGOT),
            Map.entry(Material.RAW_GOLD, Material.GOLD_INGOT),
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

    /** 钻石散布：每次工作额外产出钻石的概率。 */
    private static final double DIAMOND_SPREAD_CHANCE = 0.10;

    /** 腐化之土：每次工作额外产出腐化副产物的概率（对齐 Hypixel Corrupt Soil 约 20%）。 */
    private static final double CORRUPT_SOIL_CHANCE = 0.20;

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
     * 范围扩展：工作面积放大（对齐 Hypixel 的范围扩展玩法）。
     *
     * <p>注意：对称方形工作区边长只能取奇数，无法精确表达 +5% ——
     * 在默认 5x5 下「面积 ≥ 105%」的最小奇数边即 7x7。此处选择满足
     * 「面积不低于目标」的最小奇数边长（向上取整到最近奇数），
     * 保证模块在小半径下也确实生效；GUI 文案只描述"扩大工作范围"，
     * 不承诺精确百分比。</p>
     */
    public int radiusFor(Minion minion, int baseRadius) {
        return radiusWithExpander(baseRadius, minion.hasUpgrade(MinionUpgradeType.MINION_EXPANDER));
    }

    /**
     * 纯函数：是否装范围扩展 → 实际工作半径。实际工作与 GUI 展示共用同一口径，
     * 避免信息卡永远写 5x5、玩家以为模块无效。
     */
    public static int radiusWithExpander(int baseRadius, boolean equipped) {
        return equipped ? radiusForArea(baseRadius, EXPANDER_AREA_BONUS) : baseRadius;
    }

    /** 纯函数：面积放大 {@code areaBonus} 后反推工作半径（边长保持奇数以维持中心对称），供单测验证。 */
    static int radiusForArea(int baseRadius, double areaBonus) {
        int side = 2 * baseRadius + 1;
        double expandedSide = side * Math.sqrt(areaBonus);
        int newSide = (int) Math.ceil(expandedSide);
        if (newSide % 2 == 0) {
            newSide++; // 保持奇数，保证对称
        }
        return Math.max(baseRadius, (newSide - 1) / 2); // 永不缩小
    }

    /** 是否装备了自动售卖模块。 */
    public boolean hasAutoSell(Minion minion) {
        return minion.hasUpgrade(MinionUpgradeType.AUTO_SELLER);
    }

    /**
     * 漏斗即时售卖的价格系数（对齐 Hypixel Hopper 家族）：
     * 附魔漏斗 90%、简易漏斗 50%；两者都装取更高档；未装返回 0（不即时售卖）。
     * 与 {@link #hasAutoSell} 的"满仓全价卖"互补：漏斗无需等满仓，但折价。
     */
    public double instantSellRatio(Minion minion) {
        return hopperRatioOf(
                minion.hasUpgrade(MinionUpgradeType.BUDGET_HOPPER),
                minion.hasUpgrade(MinionUpgradeType.ENCHANTED_HOPPER));
    }

    /** 纯函数：由两档漏斗装备状态得出价格系数（附魔 90% 优先于简易 50%，都无=0），供单测验证。 */
    static double hopperRatioOf(boolean budget, boolean enchanted) {
        if (enchanted) {
            return 0.90;
        }
        if (budget) {
            return 0.50;
        }
        return 0.0;
    }

    /** 是否装备了任一即时售卖漏斗（简易/附魔）。 */
    public boolean hasInstantHopper(Minion minion) {
        return instantSellRatio(minion) > 0.0;
    }

    /**
     * 掉落物处理链：自动熔炼 -> 钻石散布 -> 腐化之土。
     * 返回全新列表，不修改入参 ItemStack。
     *
     * <p>压缩类模块不在此结算：单次掉落只有几个散装，永远凑不够压缩比例，
     * 改由 {@link #compactStorage} 按全仓总量结算（对齐 Hypixel「产出即升级材料」闭环）。</p>
     */
    public List<ItemStack> processDrops(Minion minion, List<ItemStack> drops, Random random) {
        List<ItemStack> result = new ArrayList<>(drops);
        if (minion.hasUpgrade(MinionUpgradeType.AUTO_SMELTER)) {
            result = smelt(result);
        }
        if (minion.hasUpgrade(MinionUpgradeType.DIAMOND_SPREADING) && random.nextDouble() < DIAMOND_SPREAD_CHANCE) {
            result.add(new ItemStack(Material.DIAMOND, 1));
        }
        // 腐化之土（对齐 Hypixel Corrupt Soil）：额外产出硫磺（火药）+ 腐化碎片（下界疣承载"腐化"观感），
        // 稀有转化材料，供高阶配方回流；只加不改，不影响主产物。
        if (minion.hasUpgrade(MinionUpgradeType.CORRUPT_SOIL) && random.nextDouble() < CORRUPT_SOIL_CHANCE) {
            result.add(new ItemStack(Material.GUNPOWDER, 1));
            if (random.nextDouble() < 0.5) {
                result.add(new ItemStack(Material.NETHER_WART, 1));
            }
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

    // ------------------------------------------------------------------
    // 仓储级压缩结算（自动压缩 / 超级压缩 3000）
    // ------------------------------------------------------------------

    /**
     * 仓储级压缩结算：按<b>全仓散装总量</b>聚合换算（必须在仆从所在 region 线程调用）。
     *
     * <p>旧实现按「单次掉落堆」判定，一次工作只掉 1~4 个，永远凑不够 160/9 的
     * 压缩比例 → 模块形同虚设。现改为每轮处理前扫全仓：超级压缩按附魔资源
     * ratio（160:1，末影珍珠 32:1）把散装压成<b>附魔资源</b>，无附魔定义的材质
     * 回退 9:1 方块；自动压缩只压 9:1 方块形态。</p>
     */
    public void compactStorage(Minion minion) {
        boolean superMode = minion.hasUpgrade(MinionUpgradeType.SUPER_COMPACTOR);
        if (!superMode && !minion.hasUpgrade(MinionUpgradeType.COMPACTOR)) {
            return;
        }
        // 单遍聚合散装总量（按材质去重；附魔资源/自定义物品带 meta，VanillaRef 不会误计）
        Map<Material, Long> totals = new LinkedHashMap<>();
        for (ItemStack item : minion.storageItems()) {
            if (item.hasItemMeta()) {
                continue;
            }
            totals.merge(item.getType(), (long) item.getAmount(), Long::sum);
        }
        for (Map.Entry<Material, Long> e : totals.entrySet()) {
            compactOne(minion, e.getKey(), e.getValue(), superMode);
        }
    }

    /** 对单种材质做全仓压缩：先扣散装再入成品（压缩后体积必然更小，腾出的格位足够放置）。 */
    private void compactOne(Minion minion, Material mat, long count, boolean superMode) {
        if (superMode) {
            var enchanted = EnchantedResource.ofBase(mat);
            if (enchanted.isPresent()) {
                int ratio = enchanted.get().ratio();
                if (count < ratio) {
                    return;
                }
                long made = compressCount(count, ratio)[0];
                if (minion.consume(new ItemRef.VanillaRef(mat), made * ratio)) {
                    minion.addToStorage(enchanted.get().createItem((int) made));
                }
                return;
            }
        }
        // 自动压缩（及超级压缩对无附魔定义材质的回退）：9:1 方块形态
        Material target = COMPACT_MAP.get(mat);
        if (target == null || count < 9) {
            return;
        }
        long made = compressCount(count, 9)[0];
        if (minion.consume(new ItemRef.VanillaRef(mat), made * 9)) {
            minion.addToStorage(new ItemStack(target, (int) made));
        }
    }

    /** 纯函数：压缩换算，返回 [压缩数量, 余数]，供单测验证。 */
    static long[] compressCount(long amount, int ratio) {
        return new long[]{amount / ratio, amount % ratio};
    }
}
