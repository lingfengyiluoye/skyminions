package com.hcs.minions.util;

import org.bukkit.Material;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 材料获取指南：为升级/合成材料提供「原版名称 + 合成方式 + 其他来源」的静态知识表。
 *
 * <p>用途：
 * <ul>
 *   <li>升级对比卡/合成台信息卡材料行的悬浮提示（{@link #wrapHover}）；</li>
 *   <li>合成台「材料指南」按钮与 {@code /minion materials} 命令的聊天卡片输出。</li>
 * </ul>
 * 数据覆盖全部二阶精块与各类稀有秘藏；未收录材质返回空，调用方按普通材料处理。</p>
 */
public final class MaterialGuide {

    /** 单个材料的获取指引。 */
    public record Guide(String vanillaName, boolean craftable, String howToGet) {
    }

    private static final Map<Material, Guide> GUIDE = new LinkedHashMap<>();

    private static void put(Material m, String vanillaName, String howToGet) {
        GUIDE.put(m, new Guide(vanillaName, true, howToGet));
    }

    private static void putNoCraft(Material m, String vanillaName, String source) {
        GUIDE.put(m, new Guide(vanillaName, false, source));
    }

    static {
        // ---- 二阶精块（可合成，工作台） ----
        put(Material.COAL_BLOCK, "煤炭块", "工作台 9×煤炭");
        put(Material.RAW_IRON_BLOCK, "粗铁块", "工作台 9×粗铁");
        put(Material.RAW_COPPER_BLOCK, "粗铜块", "工作台 9×粗铜");
        put(Material.RAW_GOLD_BLOCK, "粗金块", "工作台 9×粗金");
        put(Material.REDSTONE_BLOCK, "红石块", "工作台 9×红石粉");
        put(Material.LAPIS_BLOCK, "青金石块", "工作台 9×青金石");
        put(Material.DIAMOND_BLOCK, "钻石块", "工作台 9×钻石");
        put(Material.EMERALD_BLOCK, "绿宝石块", "工作台 9×绿宝石");
        put(Material.QUARTZ_BLOCK, "石英块", "工作台 4×下界石英");
        put(Material.HAY_BLOCK, "干草块", "工作台 9×小麦");
        put(Material.BONE_BLOCK, "骨头块", "工作台 9×骨粉（骨头合成骨粉）");
        put(Material.PRISMARINE_BRICKS, "海晶砖", "工作台 4×海晶碎片");
        put(Material.BAMBOO_BLOCK, "竹块", "工作台 9×竹子");
        // ---- 特例：不可合成 ----
        putNoCraft(Material.CRYING_OBSIDIAN, "哭泣的黑曜石", "猪灵以物易物 / 废墟传送门天然生成");
        // ---- 稀有秘藏 ----
        putNoCraft(Material.ANVIL, "铁砧", "工作台 3×铁块 + 4×铁锭");
        putNoCraft(Material.SPYGLASS, "望远镜", "工作台 2×铜锭 + 1×紫水晶碎片");
        putNoCraft(Material.TNT, "炸药", "工作台 5×火药 + 4×沙子");
        putNoCraft(Material.EXPERIENCE_BOTTLE, "附魔之瓶", "附魔师村民出售 / 掠夺者前哨站战利品");
        putNoCraft(Material.ECHO_SHARD, "回响碎片", "深暗之域远古城市宝箱");
        putNoCraft(Material.TOTEM_OF_UNDYING, "不死图腾", "击杀袭击队长（灾厄巡逻队首领）");
        putNoCraft(Material.GHAST_TEAR, "恶魂之泪", "击杀恶魂");
        putNoCraft(Material.ENCHANTED_GOLDEN_APPLE, "附魔金苹果", "工作台 8×金块包围 1×苹果");
        putNoCraft(Material.WITHER_ROSE, "凋零玫瑰", "生物在凋零效果下死亡时掉落");
        putNoCraft(Material.PITCHER_POD, "瓶子草荚", "嗅探兽挖掘获得");
        putNoCraft(Material.SUSPICIOUS_STEW, "迷之炖菜", "工作台 碗 + 红/棕蘑菇 + 花");
        putNoCraft(Material.DRAGON_HEAD, "龙首", "末地城末地船内天然生成");
        putNoCraft(Material.FERMENTED_SPIDER_EYE, "发酵蛛眼", "工作台 蜘蛛眼 + 糖 + 棕色蘑菇");
        putNoCraft(Material.BREWING_STAND, "酿造台", "工作台 1×烈焰棒 + 3×圆石");
        putNoCraft(Material.LEAD, "拴绳", "工作台 4×线 + 1×黏液球");
        putNoCraft(Material.CARROT_ON_A_STICK, "胡萝卜钓竿", "工作台 钓鱼竿 + 胡萝卜");
        putNoCraft(Material.ENCHANTED_BOOK, "附魔书", "附魔台附魔 / 村民交易");
        putNoCraft(Material.NAUTILUS_SHELL, "鹦鹉螺壳", "钓鱼 / 溺尸持有 / 制图师村民交易");
        putNoCraft(Material.SADDLE, "鞍", "宝箱 / 钓鱼 / 村民交易");
        putNoCraft(Material.PHANTOM_MEMBRANE, "幻翼膜", "击杀幻翼");
        putNoCraft(Material.ENDER_EYE, "末影之眼", "工作台 末影珍珠 + 烈焰粉");
        putNoCraft(Material.NAME_TAG, "命名牌", "宝箱 / 钓鱼 / 制图师村民交易");
        putNoCraft(Material.SPONGE, "海绵", "海底神殿 / 远古守卫者掉落");

        // ---- 常见基础资源来源速查（供指南命令补全显示） ----
        put(Material.ENDER_PEARL, "末影珍珠", "击杀末影人 / 猪灵以物易物");
        put(Material.BLAZE_ROD, "烈焰棒", "击杀烈焰人");
    }

    private MaterialGuide() {
    }

    public static Optional<Guide> guide(Material material) {
        return material == null ? Optional.empty() : Optional.ofNullable(GUIDE.get(material));
    }

    /**
     * 为材料行生成悬浮提示的 MiniMessage 片段（包裹原始内容）。
     * 未收录的材料原样返回；已收录时返回 {@code <hover:show_text:'…'>inner</hover>}。
     */
    public static String wrapHover(Material material, String inner) {
        Optional<Guide> g = guide(material);
        if (g.isEmpty()) {
            return inner;
        }
        StringBuilder sb = new StringBuilder("<hover:show_text:'");
        sb.append(escape(g.get().vanillaName())).append("｜").append(g.get().howToGet());
        if (!g.get().craftable()) {
            sb.append("（不可合成）");
        }
        sb.append("'>").append(inner).append("</hover>");
        return sb.toString();
    }

    /** 聊天卡片用：材料的完整指引行列表（名称行 + 获取行）。 */
    public static List<Component> chatLines(Material material) {
        List<Component> out = new ArrayList<>();
        Optional<Guide> g = guide(material);
        String name = g.map(Guide::vanillaName).orElse(null);
        TextComponent head = Component.text("· " + nameOr(material, name),
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
