package com.hcs.minions.util;

import org.bukkit.Material;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 材料获取指南：为升级/合成材料提供「原版名称 + 合成方式（含 3×3 摆法）+ 其他来源」知识表。
 *
 * <p>用途：
 * <ul>
 *   <li>{@link #wrapHover}：升级对比卡/合成台信息卡材料行的悬浮提示；</li>
 *   <li>{@link #chatLines}：「材料指南」按钮与 {@code /minion materials} 的聊天输出；</li>
 *   <li>{@link #gridOf}：合成预览 GUI 的 3×3 网格图标摆法。</li>
 * </ul>
 * 未收录材质返回空，调用方按普通材料处理。</p>
 */
public final class MaterialGuide {

    /**
     * @param vanillaName 原版物品名
     * @param howToGet    获取途径描述
     * @param grid        工作台 3×3 摆法（按行，长度恒 9；null = 空格）。空表 = 不可合成
     */
    public record Guide(String vanillaName, String howToGet, List<Material> grid) {
        public boolean craftable() {
            return !grid.isEmpty();
        }
    }

    private static final Map<Material, Guide> GUIDE = new LinkedHashMap<>();

    /** 整行铺满 9 个。 */
    private static List<Material> nine(Material m) {
        return List.of(m, m, m, m, m, m, m, m, m);
    }

    /** 左上 2×2（4 个）。注意：空格子用 null 占位，必须用可含 null 的列表实现。 */
    private static List<Material> four(Material m) {
        java.util.List<Material> cells = new ArrayList<>(Collections.nCopies(9, null));
        cells.set(0, m);
        cells.set(1, m);
        cells.set(3, m);
        cells.set(4, m);
        return cells;
    }

    private static List<Material> shaped(Material... cells) {
        if (cells.length != 9) {
            throw new IllegalArgumentException("工作台形状必须为 9 格");
        }
        // Arrays.asList 允许 null 元素（List.of 不允许）；再包一层不可变
        return Collections.unmodifiableList(java.util.Arrays.asList(cells));
    }

    private static void put(Material m, String vanillaName, String howToGet, List<Material> grid) {
        GUIDE.put(m, new Guide(vanillaName, howToGet, Collections.unmodifiableList(grid)));
    }

    private static void putNoCraft(Material m, String vanillaName, String source) {
        GUIDE.put(m, new Guide(vanillaName, source, List.of()));
    }

    static {
        // ---- 二阶精块 ----
        put(Material.COAL_BLOCK, "煤炭块", "工作台 9×煤炭", nine(Material.COAL));
        put(Material.RAW_IRON_BLOCK, "粗铁块", "工作台 9×粗铁", nine(Material.RAW_IRON));
        put(Material.RAW_COPPER_BLOCK, "粗铜块", "工作台 9×粗铜", nine(Material.RAW_COPPER));
        put(Material.RAW_GOLD_BLOCK, "粗金块", "工作台 9×粗金", nine(Material.RAW_GOLD));
        put(Material.REDSTONE_BLOCK, "红石块", "工作台 9×红石粉", nine(Material.REDSTONE));
        put(Material.LAPIS_BLOCK, "青金石块", "工作台 9×青金石", nine(Material.LAPIS_LAZULI));
        put(Material.DIAMOND_BLOCK, "钻石块", "工作台 9×钻石", nine(Material.DIAMOND));
        put(Material.EMERALD_BLOCK, "绿宝石块", "工作台 9×绿宝石", nine(Material.EMERALD));
        put(Material.QUARTZ_BLOCK, "石英块", "工作台 4×下界石英（左上 2×2）", four(Material.QUARTZ));
        put(Material.HAY_BLOCK, "干草块", "工作台 9×小麦", nine(Material.WHEAT));
        put(Material.BONE_BLOCK, "骨头块", "工作台 9×骨粉（骨头可分解为骨粉）", nine(Material.BONE_MEAL));
        put(Material.PRISMARINE_BRICKS, "海晶砖", "工作台 4×海晶碎片（左上 2×2）", four(Material.PRISMARINE_SHARD));
        put(Material.BAMBOO_BLOCK, "竹块", "工作台 9×竹子", nine(Material.BAMBOO));

        // ---- 特例：不可合成 ----
        putNoCraft(Material.CRYING_OBSIDIAN, "哭泣的黑曜石", "猪灵以物易物 / 废墟传送门天然生成");

        // ---- 稀有秘藏（含可合成形状） ----
        put(Material.GOLDEN_APPLE, "金苹果", "工作台 8×金锭包围 1×苹果",
                shaped(Material.GOLD_INGOT, Material.GOLD_INGOT, Material.GOLD_INGOT,
                        Material.GOLD_INGOT, Material.APPLE, Material.GOLD_INGOT,
                        Material.GOLD_INGOT, Material.GOLD_INGOT, Material.GOLD_INGOT));
        put(Material.ENCHANTED_GOLDEN_APPLE, "附魔金苹果", "工作台 8×金块包围 1×苹果",
                shaped(Material.GOLD_BLOCK, Material.GOLD_BLOCK, Material.GOLD_BLOCK,
                        Material.GOLD_BLOCK, Material.APPLE, Material.GOLD_BLOCK,
                        Material.GOLD_BLOCK, Material.GOLD_BLOCK, Material.GOLD_BLOCK));
        put(Material.ANVIL, "铁砧", "工作台 3×铁块 + 5×铁锭",
                shaped(Material.IRON_BLOCK, Material.IRON_BLOCK, Material.IRON_BLOCK,
                        null, Material.IRON_INGOT, null,
                        Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT));
        put(Material.SPYGLASS, "望远镜", "工作台 1×紫水晶碎片 + 2×铜锭（竖列）",
                shaped(null, Material.AMETHYST_SHARD, null,
                        null, Material.COPPER_INGOT, null,
                        null, Material.COPPER_INGOT, null));
        put(Material.TNT, "炸药", "工作台 5×火药 + 4×沙子",
                shaped(Material.GUNPOWDER, Material.SAND, Material.GUNPOWDER,
                        Material.SAND, Material.GUNPOWDER, Material.SAND,
                        Material.GUNPOWDER, Material.SAND, Material.GUNPOWDER));
        put(Material.GOLDEN_CARROT, "金胡萝卜", "工作台 8×金粒包围 1×胡萝卜",
                shaped(Material.GOLD_NUGGET, Material.GOLD_NUGGET, Material.GOLD_NUGGET,
                        Material.GOLD_NUGGET, Material.CARROT, Material.GOLD_NUGGET,
                        Material.GOLD_NUGGET, Material.GOLD_NUGGET, Material.GOLD_NUGGET));
        put(Material.GLISTERING_MELON_SLICE, "闪烁的西瓜片", "工作台 8×金粒包围 1×西瓜片",
                shaped(Material.GOLD_NUGGET, Material.GOLD_NUGGET, Material.GOLD_NUGGET,
                        Material.GOLD_NUGGET, Material.MELON_SLICE, Material.GOLD_NUGGET,
                        Material.GOLD_NUGGET, Material.GOLD_NUGGET, Material.GOLD_NUGGET));
        put(Material.SUSPICIOUS_STEW, "迷之炖菜", "工作台 碗 + 红/棕蘑菇 + 花（花朵决定效果）",
                shaped(null, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
                        Material.POPPY, Material.BOWL, null,
                        null, null, null));
        put(Material.FERMENTED_SPIDER_EYE, "发酵蛛眼", "工作台 蜘蛛眼 + 糖 + 棕色蘑菇（竖列）",
                shaped(null, Material.SUGAR, null,
                        null, Material.SPIDER_EYE, null,
                        null, Material.BROWN_MUSHROOM, null));
        put(Material.BREWING_STAND, "酿造台", "工作台 1×烈焰棒 + 3×圆石",
                shaped(null, Material.BLAZE_ROD, null,
                        Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
                        null, null, null));
        put(Material.LEAD, "拴绳", "工作台 4×线 + 1×黏液球",
                shaped(Material.STRING, Material.STRING, null,
                        Material.STRING, Material.SLIME_BALL, null,
                        null, null, Material.STRING));
        put(Material.CARROT_ON_A_STICK, "胡萝卜钓竿", "工作台 钓鱼竿 + 胡萝卜（竖列）",
                shaped(null, Material.FISHING_ROD, null,
                        null, Material.CARROT, null,
                        null, null, null));
        put(Material.ENDER_EYE, "末影之眼", "工作台 末影珍珠 + 烈焰粉（竖列）",
                shaped(null, Material.BLAZE_POWDER, null,
                        null, Material.ENDER_PEARL, null,
                        null, null, null));
        putNoCraft(Material.ENCHANTED_BOOK, "附魔书", "附魔台附魔 / 村民交易");
        putNoCraft(Material.NAME_TAG, "命名牌", "宝箱 / 钓鱼 / 制图师村民交易");
        putNoCraft(Material.NAUTILUS_SHELL, "鹦鹉螺壳", "钓鱼 / 溺尸持有 / 制图师村民交易");
        putNoCraft(Material.SADDLE, "鞍", "宝箱 / 钓鱼 / 村民交易");
        putNoCraft(Material.PHANTOM_MEMBRANE, "幻翼膜", "击杀幻翼");
        putNoCraft(Material.ECHO_SHARD, "回响碎片", "深暗之域远古城市宝箱");
        putNoCraft(Material.TOTEM_OF_UNDYING, "不死图腾", "击杀袭击队长（灾厄巡逻队首领）");
        putNoCraft(Material.GHAST_TEAR, "恶魂之泪", "击杀恶魂");
        putNoCraft(Material.WITHER_ROSE, "凋零玫瑰", "生物在凋零效果下死亡时掉落");
        putNoCraft(Material.PITCHER_POD, "瓶子草荚", "嗅探兽挖掘获得");
        putNoCraft(Material.DRAGON_HEAD, "龙首", "末地城末地船内天然生成");
        putNoCraft(Material.SPONGE, "海绵", "海底神殿 / 远古守卫者掉落");

        // ---- 常见基础资源速查 ----
        put(Material.ENDER_PEARL, "末影珍珠", "击杀末影人 / 猪灵以物易物", List.of());
        put(Material.BLAZE_ROD, "烈焰棒", "击杀烈焰人", List.of());
    }

    private MaterialGuide() {
    }

    /** 全部已收录指引（测试与运维检查用）。 */
    static java.util.Collection<Guide> all() {
        return GUIDE.values();
    }

    public static Optional<Guide> guide(Material material) {
        return material == null ? Optional.empty() : Optional.ofNullable(GUIDE.get(material));
    }

    /** 工作台 3×3 摆法（9 格，null=空）；不可合成返回空表。 */
    public static List<Material> gridOf(Material material) {
        return guide(material).map(Guide::grid).orElse(List.of());
    }

    /**
     * 为材料行生成悬浮提示的 MiniMessage 片段（包裹原始内容）。
     * 未收录的材料原样返回。
     */
    public static String wrapHover(Material material, String inner) {
        Optional<Guide> g = guide(material);
        if (g.isEmpty()) {
            return inner;
        }
        Guide gd = g.get();
        StringBuilder sb = new StringBuilder("<hover:show_text:'");
        sb.append(escape(gd.vanillaName())).append("｜").append(escape(gd.howToGet()));
        if (!gd.craftable()) {
            sb.append("（不可合成）");
        }
        sb.append("'>").append(inner).append("</hover>");
        return sb.toString();
    }

    /** 聊天卡片用：材料的完整指引行列表（名称行 + 获取行）。 */
    public static List<Component> chatLines(Material material) {
        List<Component> out = new ArrayList<>();
        Optional<Guide> g = guide(material);
        TextComponent head = Component.text("· " + nameOr(material, g.map(Guide::vanillaName).orElse(null)),
                NamedTextColor.WHITE);
        out.add(head);
        if (g.isPresent()) {
            NamedTextColor c = g.get().craftable() ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
            out.add(Component.text("  [" + (g.get().craftable() ? "可合成" : "不可合成") + "] ",
                    NamedTextColor.DARK_GRAY)
                    .append(Component.text(g.get().howToGet(), c)));
        } else {
            out.add(Component.text("  常规途径获取（挖掘/击杀/交易）", NamedTextColor.GRAY));
        }
        return out;
    }

    private static String nameOr(Material m, String vanillaName) {
        // 优先展示语境化中文名（MaterialNames），括注原版名以防歧义
        String ctx = MaterialNames.of(m);
        if (vanillaName == null || vanillaName.equals(ctx)) {
            return ctx;
        }
        return ctx + "（即原版 " + vanillaName + "）";
    }

    private static String escape(String s) {
        return s.replace("'", "\\'");
    }
}
