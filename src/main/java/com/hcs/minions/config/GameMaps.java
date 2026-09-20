package com.hcs.minions.config;

import com.hcs.minions.util.Logs;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游戏数值表（配置驱动 + 内置默认兜底）。
 *
 * <p>这些表曾经硬编码在 Java 里（自动熔炼/自动压缩/伐木补种），加一种物料或改一个
 * 比例都要重新构建。现在由 config.yml 的 {@code auto-smelt:} / {@code compaction:} /
 * {@code saplings:} 段驱动，{@code /minion reload} 热重载；配置缺失或全非法时
 * 保留内置默认（与原实现逐项一致，升级后行为不变）。</p>
 *
 * <p>静态 + volatile 不可变快照，与 {@code GuiText}/{@code GuiLayout}/{@code FuelService}
 * 的热重载模式一致。</p>
 */
public final class GameMaps {

    private static volatile Map<Material, Material> smelt = defaultSmelt();
    private static volatile Map<Material, Material> compact = defaultCompact();
    private static volatile int compactRatio = 9;
    private static volatile Map<Material, Material> saplings = defaultSaplings();

    private GameMaps() {
    }

    /** 热重载：配置为空/全非法时保留当前表（不把插件置成空表）。 */
    public static void reload(PluginConfig cfg) {
        if (cfg == null) {
            return;
        }
        if (!cfg.autoSmelt().isEmpty()) {
            smelt = cfg.autoSmelt();
            Logs.info("自动熔炼映射已按配置重载（{} 项）", smelt.size());
        } else {
            Logs.warn("config.yml 未配置 auto-smelt，自动熔炼继续使用内置默认表");
        }
        if (!cfg.compaction().isEmpty()) {
            compact = cfg.compaction();
            compactRatio = cfg.compactionRatio();
            Logs.info("自动压缩映射已按配置重载（{} 项，比例 {}:1）", compact.size(), compactRatio);
        } else {
            Logs.warn("config.yml 未配置 compaction.map，自动压缩继续使用内置默认表");
        }
        if (!cfg.saplings().isEmpty()) {
            saplings = cfg.saplings();
            Logs.info("伐木补种映射已按配置重载（{} 项）", saplings.size());
        } else {
            Logs.warn("config.yml 未配置 saplings，伐木补种继续使用内置默认表");
        }
    }

    /** 自动熔炼映射（输入 -> 产物），不可变快照。 */
    public static Map<Material, Material> smelt() {
        return smelt;
    }

    /** 自动压缩映射（散装 -> 方块形态），不可变快照。 */
    public static Map<Material, Material> compact() {
        return compact;
    }

    /** 压缩比例（默认 9:1）。 */
    public static int compactRatio() {
        return compactRatio;
    }

    /** 伐木补种映射（原木 -> 树苗），不可变快照。 */
    public static Map<Material, Material> saplings() {
        return saplings;
    }

    // ------------------------------------------------------------------
    // 内置默认表（config.yml 缺段时使用）
    // ------------------------------------------------------------------

    private static Map<Material, Material> defaultSmelt() {
        Map<Material, Material> m = new LinkedHashMap<>();
        m.put(Material.COAL_ORE, Material.COAL);
        m.put(Material.DEEPSLATE_COAL_ORE, Material.COAL);
        m.put(Material.IRON_ORE, Material.IRON_INGOT);
        m.put(Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT);
        m.put(Material.COPPER_ORE, Material.COPPER_INGOT);
        m.put(Material.DEEPSLATE_COPPER_ORE, Material.COPPER_INGOT);
        m.put(Material.GOLD_ORE, Material.GOLD_INGOT);
        m.put(Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT);
        m.put(Material.DIAMOND_ORE, Material.DIAMOND);
        m.put(Material.DEEPSLATE_DIAMOND_ORE, Material.DIAMOND);
        m.put(Material.EMERALD_ORE, Material.EMERALD);
        m.put(Material.DEEPSLATE_EMERALD_ORE, Material.EMERALD);
        m.put(Material.REDSTONE_ORE, Material.REDSTONE);
        m.put(Material.DEEPSLATE_REDSTONE_ORE, Material.REDSTONE);
        m.put(Material.LAPIS_ORE, Material.LAPIS_LAZULI);
        m.put(Material.DEEPSLATE_LAPIS_ORE, Material.LAPIS_LAZULI);
        m.put(Material.NETHER_GOLD_ORE, Material.GOLD_NUGGET);
        // 模拟采集的产物是「原矿」形态（RAW_*），不是矿石方块
        m.put(Material.RAW_IRON, Material.IRON_INGOT);
        m.put(Material.RAW_COPPER, Material.COPPER_INGOT);
        m.put(Material.RAW_GOLD, Material.GOLD_INGOT);
        m.put(Material.SAND, Material.GLASS);
        m.put(Material.RED_SAND, Material.GLASS);
        return Map.copyOf(m);
    }

    private static Map<Material, Material> defaultCompact() {
        Map<Material, Material> m = new LinkedHashMap<>();
        m.put(Material.COAL, Material.COAL_BLOCK);
        m.put(Material.IRON_INGOT, Material.IRON_BLOCK);
        m.put(Material.COPPER_INGOT, Material.COPPER_BLOCK);
        m.put(Material.GOLD_INGOT, Material.GOLD_BLOCK);
        m.put(Material.DIAMOND, Material.DIAMOND_BLOCK);
        m.put(Material.EMERALD, Material.EMERALD_BLOCK);
        m.put(Material.REDSTONE, Material.REDSTONE_BLOCK);
        m.put(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK);
        m.put(Material.RAW_IRON, Material.RAW_IRON_BLOCK);
        m.put(Material.RAW_GOLD, Material.RAW_GOLD_BLOCK);
        m.put(Material.RAW_COPPER, Material.RAW_COPPER_BLOCK);
        m.put(Material.WHEAT, Material.HAY_BLOCK);
        m.put(Material.MELON_SLICE, Material.MELON);
        m.put(Material.SNOWBALL, Material.SNOW_BLOCK);
        m.put(Material.SLIME_BALL, Material.SLIME_BLOCK);
        m.put(Material.CLAY_BALL, Material.CLAY);
        m.put(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK);
        m.put(Material.IRON_NUGGET, Material.IRON_INGOT);
        m.put(Material.GOLD_NUGGET, Material.GOLD_INGOT);
        return Map.copyOf(m);
    }

    private static Map<Material, Material> defaultSaplings() {
        Map<Material, Material> m = new EnumMap<>(Material.class);
        m.put(Material.OAK_LOG, Material.OAK_SAPLING);
        m.put(Material.SPRUCE_LOG, Material.SPRUCE_SAPLING);
        m.put(Material.BIRCH_LOG, Material.BIRCH_SAPLING);
        m.put(Material.JUNGLE_LOG, Material.JUNGLE_SAPLING);
        m.put(Material.ACACIA_LOG, Material.ACACIA_SAPLING);
        m.put(Material.DARK_OAK_LOG, Material.DARK_OAK_SAPLING);
        m.put(Material.MANGROVE_LOG, Material.MANGROVE_PROPAGULE);
        m.put(Material.CHERRY_LOG, Material.CHERRY_SAPLING);
        // 下界巨型菌树：补种对应真菌（菌岩上可正常生长）
        m.put(Material.CRIMSON_STEM, Material.CRIMSON_FUNGUS);
        m.put(Material.WARPED_STEM, Material.WARPED_FUNGUS);
        return Map.copyOf(m);
    }
}
