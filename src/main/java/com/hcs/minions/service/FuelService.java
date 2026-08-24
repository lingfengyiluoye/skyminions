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

    /**
     * 燃料属性（双轴制，对齐 Hypixel 的速度燃料与催化剂类产量倍率）：
     *
     * @param durationTicks 持续 tick（永久燃料为 0，不衰减）
     * @param boost         速度加成（1.0=无加成，1.5=快 50%）
     * @param permanent     是否永久（不随时间衰减）
     * @param multiplier    产量倍率（催化剂轴；1.0=无。产出数量 ×multiplier）
     */
    public record FuelValue(long durationTicks, double boost, boolean permanent, double multiplier) {

        /** 普通限时速度燃料。 */
        public static FuelValue timed(long durationTicks, double boost) {
            return new FuelValue(durationTicks, boost, false, 1.0);
        }

        /** 永久燃料（不随时间衰减）。 */
        public static FuelValue permanent(double boost) {
            return new FuelValue(0L, boost, true, 1.0);
        }

        /** 催化剂类限时产量倍率燃料（不加速度）。 */
        public static FuelValue catalyst(long durationTicks, double multiplier) {
            return new FuelValue(durationTicks, 1.0, false, multiplier);
        }

        /** 是否带产量倍率。 */
        public boolean hasMultiplier() {
            return multiplier > 1.0;
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
        // 催化剂类（产量倍率轴，对齐 Hypixel Catalyst 思路：数量翻倍而非速度）
        values.put(Material.AMETHYST_SHARD, FuelValue.catalyst(36000L, 1.5));   // ×1.5 / 30 分钟
        values.put(Material.BLAZE_POWDER, FuelValue.catalyst(18000L, 2.0));     // ×2.0 / 15 分钟
        values.put(Material.PHANTOM_MEMBRANE, FuelValue.catalyst(9600L, 3.0));  // ×3.0 / 8 分钟
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
