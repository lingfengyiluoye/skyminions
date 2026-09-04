package com.hcs.minions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 附魔资源（对齐 Hypixel「Enchanted Resource」中间层，纯原版实现）。
 *
 * <p>Hypixel 的升级材料核心是「附魔资源」——如 <i>附魔煤炭</i> = 160 个煤炭压缩而成，
 * 带附魔光效、独立中文名，作为高 Tier 升级配方的浓缩载体。本类用
 * <b>原版物品 + 附魔光效 + PDC 身份标记</b> 复刻它，不依赖任何外部插件：</p>
 * <ul>
 *   <li>{@link #createItem}：产出「原版材质 + 隐藏附魔光效 + PDC 标记 + 中文名」的物品；</li>
 *   <li>{@link #parse}：读取 PDC 标记还原附魔资源身份（原版同材质物品不会误判）；</li>
 *   <li>{@link #ratio}：该附魔资源相当于多少个基础物品（默认 160，对齐 Hypixel 主流值）。</li>
 * </ul>
 *
 * <p>身份标记走 PDC 键 {@code minion_enchanted=<key>}，与普通原版物品彻底区分：
 * 附魔煤炭在 GUI/仓库/合成台里都是独立物品，玩家无法用普通煤炭冒充。</p>
 */
public final class EnchantedResource {

    /** PDC 身份键（在 {@link #init} 时用插件实例构造一次）。 */
    private static volatile NamespacedKey key;

    /** 注册表：基础材质 -> 附魔资源定义（保序，供展示/指南遍历）。 */
    private static final Map<Material, EnchantedResource> BY_BASE = new LinkedHashMap<>();
    /** 注册表：附魔资源 key -> 定义（PDC 反查用）。 */
    private static final Map<String, EnchantedResource> BY_KEY = new LinkedHashMap<>();

    private final String resourceKey;
    private final String displayName;
    private final Material base;
    private final int ratio;

    private EnchantedResource(String resourceKey, String displayName, Material base, int ratio) {
        this.resourceKey = resourceKey;
        this.displayName = displayName;
        this.base = base;
        this.ratio = Math.max(1, ratio);
    }

    /** 组合根启动时调用一次，注入 PDC 键并装载内置附魔资源表。 */
    public static void init(JavaPlugin plugin) {
        key = new NamespacedKey(plugin, "minion_enchanted");
        if (!BY_BASE.isEmpty()) {
            return; // 幂等：/minion reload 重入不重复注册
        }
        // 标准 160:1（Hypixel 主流附魔资源换算）；覆盖全部仆从产物。
        // 方案 B：矿石类附魔资源基于熔炼后的锭形态（RAW_* 需先经「自动熔炼」模块变锭）。
        // ---- 采矿 ----
        register("coal", "附魔煤炭", Material.COAL, 160);
        register("iron", "附魔铁锭", Material.IRON_INGOT, 160);
        register("copper", "附魔铜锭", Material.COPPER_INGOT, 160);
        register("gold", "附魔金锭", Material.GOLD_INGOT, 160);
        register("redstone", "附魔红石", Material.REDSTONE, 160);
        register("lapis", "附魔青金石", Material.LAPIS_LAZULI, 160);
        register("diamond", "附魔钻石", Material.DIAMOND, 160);
        register("emerald", "附魔绿宝石", Material.EMERALD, 160);
        register("quartz", "附魔石英", Material.QUARTZ, 160);
        register("obsidian", "附魔黑曜石", Material.OBSIDIAN, 160);
        register("cobblestone", "附魔圆石", Material.COBBLESTONE, 160);
        register("ice", "附魔冰", Material.ICE, 160);
        register("snowball", "附魔雪球", Material.SNOWBALL, 160);
        register("clay", "附魔黏土球", Material.CLAY_BALL, 160);
        register("gravel", "附魔沙砾", Material.GRAVEL, 160);
        register("sand", "附魔沙子", Material.SAND, 160);
        register("gold_nugget", "附魔金粒", Material.GOLD_NUGGET, 160);
        // ---- 农业 ----
        register("wheat", "附魔小麦", Material.WHEAT, 160);
        register("carrot", "附魔胡萝卜", Material.CARROT, 160);
        register("potato", "附魔马铃薯", Material.POTATO, 160);
        register("pumpkin", "附魔南瓜", Material.PUMPKIN, 160);
        register("melon", "附魔西瓜", Material.MELON_SLICE, 160);
        register("beetroot", "附魔甜菜", Material.BEETROOT, 160);
        register("cocoa", "附魔可可豆", Material.COCOA_BEANS, 160);
        register("nether_wart", "附魔下界疣", Material.NETHER_WART, 160);
        register("sugar_cane", "附魔甘蔗", Material.SUGAR_CANE, 160);
        register("cactus", "附魔仙人掌", Material.CACTUS, 160);
        register("poppy", "附魔花卉", Material.POPPY, 160);
        register("red_mushroom", "附魔蘑菇", Material.RED_MUSHROOM, 160);
        register("honeycomb", "附魔蜂巢", Material.HONEYCOMB, 160);
        // ---- 林业 ----
        register("oak_log", "附魔橡木", Material.OAK_LOG, 160);
        register("birch_log", "附魔白桦木", Material.BIRCH_LOG, 160);
        register("spruce_log", "附魔云杉木", Material.SPRUCE_LOG, 160);
        register("jungle_log", "附魔丛林木", Material.JUNGLE_LOG, 160);
        register("acacia_log", "附魔金合欢木", Material.ACACIA_LOG, 160);
        register("dark_oak_log", "附魔深色橡木", Material.DARK_OAK_LOG, 160);
        register("cherry_log", "附魔樱花木", Material.CHERRY_LOG, 160);
        register("mangrove_log", "附魔红树木", Material.MANGROVE_LOG, 160);
        register("crimson_stem", "附魔绯红菌柄", Material.CRIMSON_STEM, 160);
        register("warped_stem", "附魔诡异菌柄", Material.WARPED_STEM, 160);
        // ---- 钓鱼 ----
        register("cod", "附魔鳕鱼", Material.COD, 160);
        // ---- 战斗 ----
        register("rotten_flesh", "附魔腐肉", Material.ROTTEN_FLESH, 160);
        register("bone", "附魔骨头", Material.BONE, 160);
        register("gunpowder", "附魔火药", Material.GUNPOWDER, 160);
        register("string", "附魔线", Material.STRING, 160);
        register("ender_pearl", "附魔末影珍珠", Material.ENDER_PEARL, 32);
        register("blaze_rod", "附魔烈焰棒", Material.BLAZE_ROD, 160);
        register("slimeball", "附魔黏液球", Material.SLIME_BALL, 160);
        register("magma_cream", "附魔岩浆膏", Material.MAGMA_CREAM, 160);
        // ---- 畜牧 ----
        register("beef", "附魔牛肉", Material.BEEF, 160);
        register("mutton", "附魔羊肉", Material.MUTTON, 160);
        register("chicken", "附魔鸡肉", Material.CHICKEN, 160);
        register("pork", "附魔猪排", Material.PORKCHOP, 160);
        register("rabbit", "附魔兔肉", Material.RABBIT, 160);
        register("leather", "附魔皮革", Material.LEATHER, 160);
    }

    private static void register(String k, String name, Material base, int ratio) {
        EnchantedResource r = new EnchantedResource(k, name, base, ratio);
        BY_BASE.put(base, r);
        BY_KEY.put(k, r);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 该基础材质是否有对应附魔资源（超级压缩产物用）。 */
    public static Optional<EnchantedResource> ofBase(Material base) {
        return Optional.ofNullable(BY_BASE.get(base));
    }

    /** 按 key 取附魔资源（配置解析用）。 */
    public static Optional<EnchantedResource> ofKey(String k) {
        return k == null ? Optional.empty() : Optional.ofNullable(BY_KEY.get(k.toLowerCase(Locale.ROOT)));
    }

    /** 全部已注册附魔资源（保序快照，供材料总览 GUI 遍历）。 */
    public static java.util.Collection<EnchantedResource> all() {
        return java.util.List.copyOf(BY_KEY.values());
    }

    /** 若 stack 是附魔资源返回其定义，否则空（读 PDC，原版同材质物品不误判）。 */
    public static Optional<EnchantedResource> parse(ItemStack stack) {
        if (stack == null || key == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String k = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return ofKey(k);
    }

    public String resourceKey() {
        return resourceKey;
    }

    public String displayName() {
        return displayName;
    }

    public Material base() {
        return base;
    }

    public int ratio() {
        return ratio;
    }

    // ------------------------------------------------------------------
    // 物品工厂
    // ------------------------------------------------------------------

    /** 产出附魔资源物品：原版材质 + 附魔光效 + PDC 标记 + 中文名。 */
    public ItemStack createItem(int amount) {
        ItemStack item = new ItemStack(base, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(displayName, NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("附魔资源 · 相当于 " + ratio + " 个" + MaterialNames.of(base),
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("仆从升级材料", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        // 附魔光效（隐藏附魔标签，只保留发光）
        meta.addEnchant(Enchantment.INFINITY, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, resourceKey);
        item.setItemMeta(meta);
        return item;
    }
}
