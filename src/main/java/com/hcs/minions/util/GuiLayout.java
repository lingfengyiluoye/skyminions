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

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private static int sizeOf(String key) {
        return key.startsWith("fuel-gui.") ? 27 : 54;
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

        private static final int[] RANGE_9_44 = {
                9, 10, 11, 12, 13, 14, 15, 16, 17,
                18, 19, 20, 21, 22, 23, 24, 25, 26,
                27, 28, 29, 30, 31, 32, 33, 34, 35,
                36, 37, 38, 39, 40, 41, 42, 43, 44
        };

        static {
            // ---- 仆从仓库 GUI（54 格） ----
            SLOTS_DEFAULTS.put("storage.slots", RANGE_9_44);
            SLOTS_DEFAULTS.put("storage.decor.slots", new int[]{1, 2, 6, 8, 45, 46});
            SLOT_DEFAULTS.put("storage.fuel.slot", 0);
            SLOT_DEFAULTS.put("storage.info.slot", 3);
            SLOT_DEFAULTS.put("storage.head.slot", 4);
            SLOT_DEFAULTS.put("storage.upgrade.slot", 5);
            SLOT_DEFAULTS.put("storage.skin.slot", 7);
            SLOT_DEFAULTS.put("storage.module1.slot", 47);
            SLOT_DEFAULTS.put("storage.module2.slot", 48);
            SLOT_DEFAULTS.put("storage.collect.slot", 49);
            SLOT_DEFAULTS.put("storage.autosell.slot", 50);
            SLOT_DEFAULTS.put("storage.layout.slot", 51);
            SLOT_DEFAULTS.put("storage.pickup.slot", 52);
            SLOT_DEFAULTS.put("storage.close.slot", 53);

            MATERIAL_DEFAULTS.put("storage.info.material", Material.BOOK);
            MATERIAL_DEFAULTS.put("storage.upgrade.material-ok", Material.GOLD_INGOT);
            MATERIAL_DEFAULTS.put("storage.upgrade.material-lack", Material.FURNACE);
            MATERIAL_DEFAULTS.put("storage.upgrade.material-max", Material.GOLD_INGOT);
            MATERIAL_DEFAULTS.put("storage.skin.material", Material.LEATHER_HELMET);
            MATERIAL_DEFAULTS.put("storage.module-empty.material", Material.HOPPER);
            MATERIAL_DEFAULTS.put("storage.collect.material", Material.GOLD_BLOCK);
            MATERIAL_DEFAULTS.put("storage.autosell.material-on", Material.EMERALD);
            MATERIAL_DEFAULTS.put("storage.autosell.material-off", Material.GOLD_INGOT);
            MATERIAL_DEFAULTS.put("storage.layout.material", Material.MAP);
            MATERIAL_DEFAULTS.put("storage.pickup.material", Material.ARMOR_STAND);
            MATERIAL_DEFAULTS.put("storage.close.material", Material.BARRIER);
            MATERIAL_DEFAULTS.put("storage.decor.material", Material.BLACK_STAINED_GLASS_PANE);
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

            // ---- 升级合成 GUI（54 格，Hypixel 式 3×3 合成玩法） ----
            SLOTS_DEFAULTS.put("craft.grid.slots", new int[]{11, 12, 13, 20, 21, 22, 29, 30, 31});
            SLOT_DEFAULTS.put("craft.arrow.slot", 23);
            SLOT_DEFAULTS.put("craft.result.slot", 24);
            SLOT_DEFAULTS.put("craft.info.slot", 4);
            SLOT_DEFAULTS.put("craft.back.slot", 49);
            SLOTS_DEFAULTS.put("craft.decor.slots", new int[]{
                    0, 1, 2, 3, 5, 6, 7, 8,
                    9, 10, 14, 15, 16, 17,
                    18, 19, 25, 26,
                    27, 28, 32, 33, 34, 35,
                    36, 37, 38, 39, 40, 41, 42, 43, 44,
                    45, 46, 47, 48, 50, 51, 52, 53
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
