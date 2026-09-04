package com.hcs.minions.util;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GUI 布局配置引擎（gui.yml 的 layout 段）：所有 GUI 的按钮槽位与图标材质可自定义。
 *
 * <p>规则（与 {@link GuiText} 同构）：</p>
 * <ul>
 *   <li>槽位支持整数或区间字符串（{@code [9-44, 49]}），材质用大写物品名（{@code GOLD_INGOT}）；</li>
 *   <li>越界/非法值记日志并回退内置默认，删除任意键即回退；</li>
 *   <li>可 {@code /minion reload} 热重载（布局变化在下次打开对应 GUI 时生效）。</li>
 * </ul>
 *
 * <p>键前缀与界面尺寸：{@code storage.*}/{@code collection.*}/{@code craft.*} = 54 格，
 * {@code fuel-gui.*} = 27 格。槽位冲突（与存储区/卡片区重叠）不强制拦截，
 * 但渲染顺序靠后的一方会覆盖前者，服主自行避免。</p>
 */
public final class GuiLayout {

    /** 已解析的布局值（key -> Integer / int[] / Material），load 时整体重建（volatile 热重载安全）。 */
    private static volatile Map<String, Object> values = Map.of();

    private GuiLayout() {
    }

    /** 从 gui.yml 的 layout 段加载；可重复调用（热重载）。yaml 为 null 时仅用内置默认。 */
    public static void load(YamlConfiguration yaml) {
        Map<String, Object> out = new HashMap<>();
        if (yaml != null) {
            for (String key : Defaults.SLOT_DEFAULTS.keySet()) {
                readSlot(out, yaml, key);
            }
            for (String key : Defaults.SLOTS_DEFAULTS.keySet()) {
                readSlots(out, yaml, key);
            }
            for (Map.Entry<String, Material> e : Defaults.MATERIAL_DEFAULTS.entrySet()) {
                readMaterial(out, yaml, e.getKey(), e.getValue());
            }
            // 多色材质调色板（如 storage.decor.materials）：此前漏读，导致 gui.yml 配的多色边框不生效
            for (String key : Defaults.MATERIALS_DEFAULTS.keySet()) {
                readMaterials(out, yaml, key);
            }
        }
        values = out;
        Logs.info("GUI 布局已加载（{} 项槽位/材质，来自 gui.yml layout 段）", out.size());
    }

    /** 单槽位：gui.yml 缺失/非法时回退内置默认。 */
    public static int slot(String key) {
        Object v = values.get(key);
        if (v instanceof Integer i) {
            return i;
        }
        return Defaults.SLOT_DEFAULTS.getOrDefault(key, 0);
    }

    /** 槽位数组：gui.yml 缺失/全部非法时回退内置默认。 */
    public static int[] slots(String key) {
        Object v = values.get(key);
        if (v instanceof int[] arr && arr.length > 0) {
            return arr;
        }
        return Defaults.SLOTS_DEFAULTS.getOrDefault(key, new int[0]);
    }

    /** 图标材质：gui.yml 缺失/无法识别时回退内置默认。 */
    public static Material material(String key) {
        Object v = values.get(key);
        if (v instanceof Material m) {
            return m;
        }
        return Defaults.MATERIAL_DEFAULTS.getOrDefault(key, Material.STONE);
    }

    /** 材质调色板（多色边框玻璃等）：gui.yml 为字符串列表；缺失/全非法回退内置默认（可能为空数组）。 */
    public static Material[] materials(String key) {
        Object v = values.get(key);
        if (v instanceof Material[] arr && arr.length > 0) {
            return arr;
        }
        return Defaults.MATERIALS_DEFAULTS.getOrDefault(key, new Material[0]);
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private static int sizeOf(String key) {
        if (key.startsWith("fuel-gui.") || key.startsWith("guide-list.")) {
            return 27;
        }
        return key.startsWith("preview.") ? 45 : 54;
    }

    private static void readSlot(Map<String, Object> out, YamlConfiguration yaml, String key) {
        String path = "layout." + key;
        if (!yaml.isInt(path)) {
            return;
        }
        int v = yaml.getInt(path);
        if (v < 0 || v >= sizeOf(key)) {
            Logs.warn("gui.yml layout.{} 槽位 {} 越界（0-{}），已回退默认", key, v, sizeOf(key) - 1);
            return;
        }
        out.put(key, v);
    }

    private static void readSlots(Map<String, Object> out, YamlConfiguration yaml, String key) {
        String path = "layout." + key;
        if (!yaml.isList(path)) {
            return;
        }
        int size = sizeOf(key);
        List<?> raw = yaml.getList(path);
        List<Integer> parsed = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Object o : raw) {
            for (int v : expand(o, size)) {
                if (seen.add(v)) {
                    parsed.add(v);
                }
            }
        }
        if (parsed.isEmpty()) {
            Logs.warn("gui.yml layout.{} 无有效槽位，已回退默认", key);
            return;
        }
        out.put(key, parsed.stream().mapToInt(Integer::intValue).toArray());
    }

    /** 单个配置项展开：整数或 "a-b" 区间字符串；越界项跳过并告警。 */
    private static List<Integer> expand(Object o, int size) {
        List<Integer> out = new ArrayList<>();
        if (o instanceof Number n) {
            int v = n.intValue();
            if (v >= 0 && v < size) {
                out.add(v);
            } else {
                Logs.warn("gui.yml layout 槽位 {} 越界（0-{}），已跳过", v, size - 1);
            }
            return out;
        }
        if (o instanceof String s && s.contains("-")) {
            String[] parts = s.split("-", 2);
            try {
                int from = Integer.parseInt(parts[0].trim());
                int to = Integer.parseInt(parts[1].trim());
                for (int v = Math.max(0, from); v <= to && v < size; v++) {
                    out.add(v);
                }
                return out;
            } catch (NumberFormatException ignored) {
                // 落入下方告警
            }
        }
        Logs.warn("gui.yml layout 槽位项 {} 无法识别（需整数或 a-b 区间），已跳过", o);
        return out;
    }

    private static void readMaterial(Map<String, Object> out, YamlConfiguration yaml,
                                     String key, Material def) {
        String path = "layout." + key;
        if (!yaml.isString(path)) {
            return;
        }
        Material m = matchMaterial(yaml.getString(path, ""));
        if (m == null) {
            Logs.warn("gui.yml layout.{} 材质 {} 无法识别，已回退默认 {}", key, yaml.getString(path), def);
            return;
        }
        out.put(key, m);
    }

    /** 读取材质列表（多色调色板）：gui.yml 为字符串列表，逐项解析，全非法则不覆盖默认。 */
    private static void readMaterials(Map<String, Object> out, YamlConfiguration yaml, String key) {
        String path = "layout." + key;
        if (!yaml.isList(path)) {
            return;
        }
        List<?> raw = yaml.getList(path);
        List<Material> parsed = new ArrayList<>();
        for (Object o : raw) {
            Material m = matchMaterial(String.valueOf(o));
            if (m != null) {
                parsed.add(m);
            } else {
                Logs.warn("gui.yml layout.{} 材质项 {} 无法识别，已跳过", key, o);
            }
        }
        if (parsed.isEmpty()) {
            Logs.warn("gui.yml layout.{} 无有效材质，已回退默认", key);
            return;
        }
        out.put(key, parsed.toArray(new Material[0]));
    }

    /** 按枚举名大小写不敏感匹配材质（不用 Material.matchMaterial，避免其运行时依赖，纯单测可用）。 */
    private static Material matchMaterial(String name) {
        String target = name == null ? "" : name.trim();
        for (Material m : Material.values()) {
            if (m.name().equalsIgnoreCase(target)) {
                return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 内置默认布局（与代码原硬编码一致；gui.yml 缺键回退）
    // ------------------------------------------------------------------

    private static final class Defaults {

        private static final Map<String, Integer> SLOT_DEFAULTS = new HashMap<>();
        private static final Map<String, int[]> SLOTS_DEFAULTS = new HashMap<>();
        private static final Map<String, Material> MATERIAL_DEFAULTS = new HashMap<>();
        private static final Map<String, Material[]> MATERIALS_DEFAULTS = new HashMap<>();

        private static final int[] RANGE_9_44 = {
                9, 10, 11, 12, 13, 14, 15, 16, 17,
                18, 19, 20, 21, 22, 23, 24, 25, 26,
                27, 28, 29, 30, 31, 32, 33, 34, 35,
                36, 37, 38, 39, 40, 41, 42, 43, 44
        };

        static {
            // ---- 仆从仓库 GUI（54 格，用户自定义布局）：
            //   顶行  0 燃料 | 3 信息 | 4 头颅 | 5 升级 | 6 皮肤（1/2/7/8 装饰）
            //   左列  18/27/36/45 四个模块槽（燃料下方隔一格竖排；9 为间隔装饰）
            //   间隔列 第 1 列（10/19/28/37）装饰，隔开模块列与存储区
            //   存储区 第 2-8 列、第 1-4 行 = 28 格
            //   底行  49 收集(居中) | 50 拾取 | 51 关闭 ----
            SLOTS_DEFAULTS.put("storage.slots", new int[]{
                    11, 12, 13, 14, 15, 16, 17,
                    20, 21, 22, 23, 24, 25, 26,
                    29, 30, 31, 32, 33, 34, 35,
                    38, 39, 40, 41, 42, 43, 44
            });
            SLOTS_DEFAULTS.put("storage.decor.slots", new int[]{
                    1, 2, 7, 8, 9, 10, 19, 28, 37, 46, 47, 48, 52, 53
            });
            SLOT_DEFAULTS.put("storage.fuel.slot", 0);
            SLOT_DEFAULTS.put("storage.info.slot", 3);
            SLOT_DEFAULTS.put("storage.head.slot", 4);
            SLOT_DEFAULTS.put("storage.upgrade.slot", 5);
            SLOT_DEFAULTS.put("storage.skin.slot", 6);
            SLOT_DEFAULTS.put("storage.module1.slot", 18);
            SLOT_DEFAULTS.put("storage.module2.slot", 27);
            SLOT_DEFAULTS.put("storage.module3.slot", 36);
            SLOT_DEFAULTS.put("storage.module4.slot", 45);
            SLOT_DEFAULTS.put("storage.collect.slot", 49);
            SLOT_DEFAULTS.put("storage.pickup.slot", 50);
            SLOT_DEFAULTS.put("storage.close.slot", 51);

            MATERIAL_DEFAULTS.put("storage.info.material", Material.BOOK);
            MATERIAL_DEFAULTS.put("storage.upgrade.material-ok", Material.GOLD_INGOT);
            MATERIAL_DEFAULTS.put("storage.upgrade.material-lack", Material.FURNACE);
            MATERIAL_DEFAULTS.put("storage.upgrade.material-max", Material.NETHER_STAR);
            MATERIAL_DEFAULTS.put("storage.skin.material", Material.LEATHER_HELMET);
            MATERIAL_DEFAULTS.put("storage.module-empty.material", Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            MATERIAL_DEFAULTS.put("storage.collect.material", Material.GOLD_BLOCK);
            MATERIAL_DEFAULTS.put("storage.pickup.material", Material.ARMOR_STAND);
            MATERIAL_DEFAULTS.put("storage.close.material", Material.BARRIER);
            // 边框装饰：多色玻璃调色板（逐槽循环取色，营造彩色边框）；单色回退键仍保留
            MATERIAL_DEFAULTS.put("storage.decor.material", Material.CYAN_STAINED_GLASS_PANE);
            MATERIALS_DEFAULTS.put("storage.decor.materials", new Material[]{
                    Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    Material.CYAN_STAINED_GLASS_PANE,
                    Material.BLUE_STAINED_GLASS_PANE,
                    Material.PURPLE_STAINED_GLASS_PANE
            });
            MATERIAL_DEFAULTS.put("storage.locked.material", Material.GRAY_STAINED_GLASS_PANE);

            // ---- 图鉴 GUI（54 格） ----
            SLOT_DEFAULTS.put("collection.all.slot", 0);
            SLOTS_DEFAULTS.put("collection.filter.slots", new int[]{1, 2, 3, 4, 5, 6});
            SLOT_DEFAULTS.put("collection.close.slot", 8);
            SLOTS_DEFAULTS.put("collection.card.slots", RANGE_9_44);
            SLOT_DEFAULTS.put("collection.prev.slot", 48);
            SLOT_DEFAULTS.put("collection.progress.slot", 49);
            SLOT_DEFAULTS.put("collection.next.slot", 50);

            MATERIAL_DEFAULTS.put("collection.all.material", Material.BOOK);
            MATERIAL_DEFAULTS.put("collection.icon.mining", Material.DIAMOND_PICKAXE);
            MATERIAL_DEFAULTS.put("collection.icon.farming", Material.GOLDEN_HOE);
            MATERIAL_DEFAULTS.put("collection.icon.foraging", Material.IRON_AXE);
            MATERIAL_DEFAULTS.put("collection.icon.combat", Material.DIAMOND_SWORD);
            MATERIAL_DEFAULTS.put("collection.icon.fishing", Material.FISHING_ROD);
            MATERIAL_DEFAULTS.put("collection.icon.special", Material.NETHER_STAR);
            MATERIAL_DEFAULTS.put("collection.close.material", Material.BARRIER);
            MATERIAL_DEFAULTS.put("collection.locked.material", Material.BARRIER);
            MATERIAL_DEFAULTS.put("collection.prev.material", Material.ARROW);
            MATERIAL_DEFAULTS.put("collection.next.material", Material.ARROW);
            MATERIAL_DEFAULTS.put("collection.progress.material", Material.BOOK);

            // ---- 燃料选择 GUI（27 格） ----
            SLOTS_DEFAULTS.put("fuel-gui.option.slots", new int[]{10, 11, 12, 13, 14, 15, 16});
            SLOT_DEFAULTS.put("fuel-gui.status.slot", 22);
            SLOT_DEFAULTS.put("fuel-gui.close.slot", 26);
            MATERIAL_DEFAULTS.put("fuel-gui.empty.material", Material.GRAY_STAINED_GLASS_PANE);
            MATERIAL_DEFAULTS.put("fuel-gui.close.material", Material.BARRIER);

            // ---- 升级合成 GUI（54 格，Hypixel 式 4×4 合成玩法） ----
            // 4×4=16 格：大配方（如小麦 T12：WHEAT×512=8 叠 + 附魔资源 + 金胡萝卜 + 本体）9 格装不下
            SLOTS_DEFAULTS.put("craft.grid.slots", new int[]{10, 11, 12, 13, 19, 20, 21, 22, 28, 29, 30, 31, 37, 38, 39, 40});
            SLOT_DEFAULTS.put("craft.arrow.slot", 23);
            SLOT_DEFAULTS.put("craft.result.slot", 24);
            SLOT_DEFAULTS.put("craft.info.slot", 4);
            SLOT_DEFAULTS.put("craft.back.slot", 49);
            SLOT_DEFAULTS.put("craft.guide.slot", 45);
            MATERIAL_DEFAULTS.put("craft.guide.material", Material.BOOK);

            // ---- 合成预览 GUI（45 格，纯展示：3×3 摆法 → 成品） ----
            SLOTS_DEFAULTS.put("preview.grid.slots", new int[]{20, 21, 22, 29, 30, 31, 38, 39, 40});
            SLOT_DEFAULTS.put("preview.arrow.slot", 32);
            SLOT_DEFAULTS.put("preview.result.slot", 34);
            SLOT_DEFAULTS.put("preview.info.slot", 4);
            SLOT_DEFAULTS.put("preview.prev.slot", 9);
            SLOT_DEFAULTS.put("preview.next.slot", 17);
            SLOT_DEFAULTS.put("preview.close.slot", 44);
            SLOTS_DEFAULTS.put("preview.decor.slots", new int[]{
                    0, 1, 2, 3, 5, 6, 7, 8,
                    10, 11, 12, 13, 14, 15, 16,
                    18, 19, 23, 24, 25, 26, 27, 28,
                    33, 35, 36, 37, 41, 42, 43
            });
            MATERIAL_DEFAULTS.put("preview.decor.material", Material.BLACK_STAINED_GLASS_PANE);
            MATERIAL_DEFAULTS.put("preview.info.material", Material.BOOK);
            MATERIAL_DEFAULTS.put("preview.arrow.material", Material.ARROW);
            MATERIAL_DEFAULTS.put("preview.prev.material", Material.ARROW);
            MATERIAL_DEFAULTS.put("preview.next.material", Material.ARROW);
            MATERIAL_DEFAULTS.put("preview.close.material", Material.BARRIER);
            MATERIAL_DEFAULTS.put("preview.nocraft.material", Material.BARRIER);

            // ---- 材料指南清单 GUI（27 格，两级导航第一级） ----
            SLOTS_DEFAULTS.put("guide-list.material.slots", new int[]{10, 11, 12, 13, 14, 15, 16});
            SLOT_DEFAULTS.put("guide-list.close.slot", 22);
            MATERIAL_DEFAULTS.put("guide-list.close.material", Material.BARRIER);
            MATERIAL_DEFAULTS.put("guide-list.decor.material", Material.BLACK_STAINED_GLASS_PANE);
            SLOTS_DEFAULTS.put("craft.decor.slots", new int[]{
                    0, 1, 2, 3, 5, 6, 7, 8,
                    9, 14, 15, 16, 17, 18,
                    25, 26, 27,
                    32, 33, 34, 35, 36,
                    41, 42, 43, 44,
                    46, 47, 48, 50, 51, 52, 53
            });
            MATERIAL_DEFAULTS.put("craft.arrow.material", Material.ARROW);
            MATERIAL_DEFAULTS.put("craft.decor.material", Material.BLACK_STAINED_GLASS_PANE);
            MATERIAL_DEFAULTS.put("craft.back.material", Material.ARROW);
            MATERIAL_DEFAULTS.put("craft.lack.material", Material.GRAY_STAINED_GLASS_PANE);
        }

        private Defaults() {
        }
    }
}
