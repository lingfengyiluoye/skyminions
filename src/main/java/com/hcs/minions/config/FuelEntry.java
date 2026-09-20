package com.hcs.minions.config;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Map;

/**
 * 燃料定义（Hypixel 式双轴制，配置驱动）。
 *
 * <p>config.yml {@code fuels:} 段每项：</p>
 * <pre>
 * fuels:
 *   - material: COAL          # 原版物品（必填）
 *     duration-seconds: 180   # 持续秒数（permanent: true 时省略）
 *     boost-percent: 5        # 速度加成百分比（催化剂类省略/0）
 *     multiplier: 1.0         # 产量倍率（催化剂轴，>1 时优先于 boost）
 *     returns-empty: BUCKET   # 可选：桶装燃料消耗后返还的空容器
 *     permanent: true         # 可选：永久燃料（不衰减）
 * </pre>
 *
 * <p>附魔资源作催化剂走独立的 {@code enchanted-fuels:} 段（key 为附魔资源 key），
 * 使催化剂沉淀在 collection 经济里（对齐 Hypixel 的附魔面包/干草捆）。</p>
 */
public record FuelEntry(
        /** 展示用物品材质（附魔资源取其基底材质）。 */
        Material icon,
        /** 附魔资源 key（非空表示这是附魔资源催化剂）。 */
        String enchantedKey,
        /** 显示名（附魔资源用中文名，原版燃料用 MaterialNames）。 */
        String displayName,
        /** 持续 tick（永久燃料为 0）。 */
        long durationTicks,
        /** 速度加成（1.0 = 无）。 */
        double boost,
        /** 是否永久（不衰减）。 */
        boolean permanent,
        /** 产量倍率（催化剂轴；1.0 = 无）。 */
        double multiplier,
        /** 桶装燃料返还的空容器（null = 不返还）。 */
        Material returnsEmpty
) {

    public static FuelEntry vanilla(Material icon, long durationTicks, double boost,
                                    boolean permanent, double multiplier, Material returnsEmpty) {
        return new FuelEntry(icon, null, null, durationTicks, boost, permanent, multiplier, returnsEmpty);
    }

    public static FuelEntry enchanted(String key, String displayName, Material icon,
                                      long durationTicks, double multiplier) {
        return new FuelEntry(icon, key, displayName, durationTicks, 1.0, false, multiplier, null);
    }

    /** 是否带产量倍率（催化剂轴）。 */
    public boolean hasMultiplier() {
        return multiplier > 1.0;
    }

    /** 是否桶装燃料（消耗后返空容器）。 */
    public boolean hasEmptyContainer() {
        return returnsEmpty != null;
    }

    /**
     * 燃料配置表（原版 + 附魔资源两张表，volatile 整体替换，热重载安全）。
     *
     * @param byMaterial    原版物品 -> 燃料
     * @param byEnchanted   附魔资源 key -> 燃料
     */
    public record Table(Map<Material, FuelEntry> byMaterial, Map<String, FuelEntry> byEnchanted) {

        public static Table empty() {
            return new Table(Map.of(), Map.of());
        }

        public boolean isEmpty() {
            return byMaterial.isEmpty() && byEnchanted.isEmpty();
        }
    }

    /** 从配置段解析一张燃料表；解析失败/空值告警并跳过该项。 */
    public static Table parse(java.util.List<?> rawVanilla, java.util.List<?> rawEnchanted) {
        Map<Material, FuelEntry> vanilla = new java.util.LinkedHashMap<>();
        Map<String, FuelEntry> enchanted = new java.util.LinkedHashMap<>();
        if (rawVanilla != null) {
            for (Object o : rawVanilla) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Material icon = material(m.get("material"));
                if (icon == null) {
                    com.hcs.minions.util.Logs.warn("fuels 配置项缺少有效 material，已跳过: {}", m);
                    continue;
                }
                boolean permanent = Boolean.TRUE.equals(m.get("permanent"));
                long duration = seconds(m.get("duration-seconds"));
                double boost = percent(m.get("boost-percent"));
                double mult = number(m.get("multiplier"), 1.0);
                Material empty = material(m.get("returns-empty"));
                if (!permanent && duration <= 0) {
                    com.hcs.minions.util.Logs.warn("fuels {} 未配 duration-seconds 且非 permanent，已跳过", icon);
                    continue;
                }
                vanilla.put(icon, vanilla(icon, duration, boost, permanent, mult, empty));
            }
        }
        if (rawEnchanted != null) {
            for (Object o : rawEnchanted) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String key = str(m.get("resource"));
                if (key == null || key.isBlank()) {
                    com.hcs.minions.util.Logs.warn("enchanted-fuels 配置项缺少 resource，已跳过: {}", m);
                    continue;
                }
                key = key.toLowerCase(Locale.ROOT);
                var res = com.hcs.minions.util.EnchantedResource.ofKey(key);
                if (res.isEmpty()) {
                    com.hcs.minions.util.Logs.warn("enchanted-fuels {} 不是已注册的附魔资源，已跳过", key);
                    continue;
                }
                long duration = seconds(m.get("duration-seconds"));
                if (duration <= 0) {
                    com.hcs.minions.util.Logs.warn("enchanted-fuels {} 未配 duration-seconds，已跳过", key);
                    continue;
                }
                double mult = number(m.get("multiplier"), 1.0);
                if (mult <= 1.0) {
                    com.hcs.minions.util.Logs.warn("enchanted-fuels {} 的 multiplier 必须 >1（催化剂轴），已跳过", key);
                    continue;
                }
                enchanted.put(key, enchanted(key, res.get().displayName(), res.get().base(), duration, mult));
            }
        }
        return new Table(java.util.Map.copyOf(vanilla), java.util.Map.copyOf(enchanted));
    }

    private static Material material(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Material.valueOf(String.valueOf(o).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long seconds(Object o) {
        double s = number(o, 0.0);
        return s <= 0 ? 0L : Math.round(s * 20.0);
    }

    private static double percent(Object o) {
        return 1.0 + number(o, 0.0) / 100.0;
    }

    private static double number(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
