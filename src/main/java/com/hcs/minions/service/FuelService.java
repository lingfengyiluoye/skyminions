package com.hcs.minions.service;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 燃料定义（Hypixel 式，一比一对齐）：物品 -> 燃料属性。
 *
 * <p>普通燃料提供加速且有时间限制；永久燃料（Enchanted Lava Bucket 等）提供
 * 持续加速、不随时间衰减。无燃料时仆从仍工作（燃料只加速）。</p>
 */
public final class FuelService {

    /** 燃料属性：持续 tick（永久燃料为 0，表示不衰减）+ 速度加成（1.0=无加成，1.5=快 50%）。 */
    public record FuelValue(long durationTicks, double boost, boolean permanent) {

        /** 普通限时燃料。 */
        public static FuelValue timed(long durationTicks, double boost) {
            return new FuelValue(durationTicks, boost, false);
        }

        /** 永久燃料（不随时间衰减）。 */
        public static FuelValue permanent(double boost) {
            return new FuelValue(0L, boost, true);
        }
    }

    private static final Map<Material, FuelValue> VALUES = build();

    /** 有序注册表（基础→高级→永久），顺序即燃料指引列表展示顺序。 */
    private static Map<Material, FuelValue> build() {
        Map<Material, FuelValue> values = new LinkedHashMap<>();
        // 基础燃料
        values.put(Material.COAL, FuelValue.timed(3600L, 1.05));
        values.put(Material.CHARCOAL, FuelValue.timed(3600L, 1.05));
        values.put(Material.COAL_BLOCK, FuelValue.timed(18000L, 1.05));
        // 高级燃料
        values.put(Material.LAVA_BUCKET, FuelValue.timed(72000L, 1.25));
        values.put(Material.BLAZE_ROD, FuelValue.timed(21600L, 1.30));
        // 永久燃料（Hypixel 原版：Enchanted Lava / Magma / Plasma Bucket / Solar Panel）
        values.put(Material.MAGMA_CREAM, FuelValue.permanent(1.30));
        values.put(Material.GLOWSTONE_DUST, FuelValue.permanent(1.35));
        values.put(Material.DAYLIGHT_DETECTOR, FuelValue.permanent(1.25));
        return Collections.unmodifiableMap(values);
    }

    private FuelService() {
    }

    public static boolean isFuel(Material material) {
        return material != null && VALUES.containsKey(material);
    }

    public static FuelValue valueOf(Material material) {
        return material == null ? null : VALUES.get(material);
    }

    /** 全部燃料注册表（有序，供燃料指引列表展示）。 */
    public static Map<Material, FuelValue> all() {
        return VALUES;
    }
}
