package com.hcs.minions.config;

import com.hcs.minions.model.MinionBehavior;
import com.hcs.minions.model.MinionCategory;
import com.hcs.minions.model.MinionSkin;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.util.ItemRef;
import com.hcs.minions.util.Logs;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置加载器：唯一允许接触 getConfig() 的地方。
 * 将 YAML 反序列化为强类型 {@link PluginConfig} 记录，缺失/非法值时回退到默认值并告警。
 */
public final class ConfigLoader {

    /** 固定仆从头颅贴图（base64 纹理，离线模式也可用，已经 Mojang 材质 CDN 可达性验证）。
     *  可在 config.yml 的 head-texture 覆盖。 */
    private static final String DEFAULT_HEAD_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUv"
                    + "OWFkMDlkZDVkOTQyYjZiZTZhZDYyMWYzYzNlYmJjNmM3MDFkNWMzMDQyZmFmYjJkNmJhM2ZjYTU0YTNjZDYxNyJ9fX0=";

    private ConfigLoader() {
    }

    public static PluginConfig load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        FileConfiguration yaml = plugin.getConfig();

        DatabaseConfig database = new DatabaseConfig(
                str(yaml, "database.type", "sqlite"),
                str(yaml, "database.sqlite-file", "minions.db"),
                str(yaml, "database.host", "127.0.0.1"),
                yaml.getInt("database.port", 3306),
                str(yaml, "database.database", "minions"),
                str(yaml, "database.user", "root"),
                str(yaml, "database.password", ""),
                yaml.getInt("database.pool-size", 4)
        );

        EconomyConfig economy = new EconomyConfig(
                yaml.getBoolean("economy.enabled", true),
                yaml.getBoolean("economy.auto-sell-on-full", true),
                yaml.getLong("economy.sell-interval-ticks", 400),
                yaml.getDouble("economy.price-multiplier", 1.0)
        );

        RenderConfig render = new RenderConfig(
                (float) yaml.getDouble("render.view-range", 2.0),
                (float) yaml.getDouble("render.scale", 1.0)
        );

        int maxChecks = yaml.getInt("max-checks-per-cycle", 48);
        if (maxChecks >= 50) {
            Logs.warn("max-checks-per-cycle={} 超过硬上限 50，已强制回落到 48", maxChecks);
            maxChecks = 48;
        }

        Map<String, MinionTypeConfig> types = loadTypes(yaml.getConfigurationSection("types"));

        // 皮肤纹理覆盖（skins 段）：与配置同步热重载，空值不覆盖内置纹理
        MinionSkin.loadTextures(yaml);

        CollectionConfig collections = loadCollections(yaml.getConfigurationSection("collections"));

        // 离线收益结算（三道平衡锁：仓储天花板 / 仅基础速度 / 燃料真实燃烧）
        OfflineProductionConfig offlineProduction = loadOfflineProduction(yaml.getConfigurationSection("offline-production"));

        return new PluginConfig(
                database, economy, render,
                yaml.getLong("tick-period", 20),
                maxChecks,
                yaml.getInt("max-minions-per-player", 10),
                str(yaml, "head-texture", DEFAULT_HEAD_TEXTURE),
                yaml.getBoolean("debug", false),
                types,
                collections,
                offlineProduction,
                yaml.getBoolean("upgrade-require-previous-body", true),
                yaml.getBoolean("collection-unlock-enabled", true),
                yaml.getDouble("player-scan-radius", 48.0),
                yaml.getInt("min-placement-distance", 1),
                yaml.getBoolean("rare-drop-broadcast", true)
        );
    }

    /** 解析离线收益配置（缺失时用安全默认值）。 */
    private static OfflineProductionConfig loadOfflineProduction(ConfigurationSection section) {
        if (section == null) {
            return new OfflineProductionConfig(true, 24, 100, 180);
        }
        return new OfflineProductionConfig(
                section.getBoolean("enabled", true),
                section.getInt("max-hours", 24),
                section.getInt("rate-percent", 100),
                section.getInt("min-seconds", 180)
        );
    }

    /** 解析 Collection 里程碑配置（缺失时用默认阈值）。 */
    private static CollectionConfig loadCollections(ConfigurationSection section) {
        long[] milestones = {50, 100, 250, 500, 1000, 2500, 5000, 10000};
        if (section != null && section.isList("milestones")) {
            List<Long> raw = section.getLongList("milestones");
            if (!raw.isEmpty()) {
                milestones = raw.stream().mapToLong(Long::longValue).sorted().toArray();
            }
        }
        Set<Integer> slotMilestones = new java.util.HashSet<>(Set.of(3, 5, 7));
        if (section != null && section.isList("slot-milestones")) {
            slotMilestones = new java.util.HashSet<>(section.getIntegerList("slot-milestones"));
        }
        return new CollectionConfig(
                section == null || section.getBoolean("enabled", true),
                milestones,
                section == null ? 100 : section.getLong("coins-base", 100),
                slotMilestones,
                section == null ? 5 : section.getInt("max-bonus-slots", 5)
        );
    }

    private static Map<String, MinionTypeConfig> loadTypes(ConfigurationSection section) {
        Map<String, MinionTypeConfig> out = new LinkedHashMap<>();
        if (section == null) {
            MinionType.loadAll(List.of());
            return out;
        }
        List<MinionType> kinds = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            // ---- 身份层：注册类型（行为必填，图标/分类有默认） ----
            MinionBehavior behavior = parseBehavior(s.getString("behavior"), key);
            if (behavior == null) {
                Logs.warn("仆从类型 {} 缺少有效 behavior 字段（miner/farmer/lumberjack/fisher/slayer/rancher/cobble 对应 "
                        + "mining/farming/foraging/fishing/combat/ranching/generator），已跳过", key);
                continue;
            }
            String keyLower = key.toLowerCase(Locale.ROOT);
            Material icon = parseMaterial(s.getString("icon"), defaultIcon(behavior));
            MinionCategory category = parseCategory(s.getString("category"), behavior);
            kinds.add(MinionType.of(keyLower, s.getString("display-name", key), behavior, category, icon));

            // ---- 调参层：MinionTypeConfig ----
            Set<Material> targets = EnumSet.noneOf(Material.class);
            for (String name : s.getStringList("targets")) {
                try {
                    targets.add(Material.valueOf(name.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    Logs.warn("未知目标方块: {} (仆从类型 {})", name, key);
                }
            }
            Map<Integer, Set<Material>> targetsByTier = parseTargetsByTier(s, key);
            // 分阶配方覆盖（稀有掉落回流载体）：'9': { EMERALD: 8, ... } 整行替代该级配方
            Map<Integer, Map<ItemRef, Long>> recipeOverrides = parseRecipeOverrides(s, key);
            Material product = parseMaterial(s.getString("product"), Material.COBBLESTONE);
            // 升级配方：多材料 + 陡增曲线；未配 upgrade-recipe 时回退旧 upgrade-item/upgrade-cost 单材料
            Map<ItemRef, Long> upgradeRecipe = parseRecipe(s, product, key);
            double upgradeCostGrowth = Math.max(1.0, s.getDouble("upgrade-cost-growth", 1.15));
            // 动作间隔：支持单值（全等级统一）或列表（逐级表，对齐 Hypixel 原版提速曲线）
            int[] cooldownPerLevel;
            int cooldownBase;
            Object cdRaw = s.get("cooldown-ticks");
            if (cdRaw instanceof List<?> cdList && !cdList.isEmpty()) {
                cooldownPerLevel = cdList.stream().mapToInt(o -> o instanceof Number n ? n.intValue() : 0).toArray();
                cooldownBase = cooldownPerLevel[0];
            } else {
                cooldownBase = s.getInt("cooldown-ticks", 20);
                cooldownPerLevel = new int[0];
            }
            // 专属稀有掉落（可空）：未配置或配置错误则关闭
            Material rareDrop = parseMaterialNullable(s.getString("rare-drop"));
            double rareDropChance = s.getDouble("rare-drop-chance", 0.0);
            if (rareDrop == null && rareDropChance > 0) {
                Logs.warn("仆从类型 {} 配置了 rare-drop-chance 但无有效 rare-drop，稀有掉落已关闭", key);
                rareDropChance = 0;
            }
            out.put(keyLower, new MinionTypeConfig(
                    key,
                    s.getString("display-name", key),
                    s.getInt("max-level", 11),
                    s.getDouble("base-efficiency", 1.0),
                    s.getDouble("efficiency-per-level", 0.1),
                    s.getLong("base-fuel-ticks", 72000),
                    cooldownBase,
                    s.getDouble("sell-price-per-unit", 1.0),
                    product,
                    upgradeRecipe,
                    upgradeCostGrowth,
                    s.getInt("base-radius", 2),
                    s.getInt("harvest-cap", 1),
                    cooldownPerLevel,
                    targets,
                    targetsByTier,
                    rareDrop,
                    rareDropChance,
                    s.getLong("unlock-amount", 0),
                    recipeOverrides,
                    java.util.Set.copyOf(s.getStringList("preferred-targets")),
                    s.getString("ranch-animal")
            ));
        }
        MinionType.loadAll(kinds);
        return out;
    }

    private static MinionBehavior parseBehavior(String raw, String key) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MinionBehavior.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Logs.warn("仆从类型 {} 的 behavior 无效: {}", key, raw);
            return null;
        }
    }

    private static MinionCategory parseCategory(String raw, MinionBehavior behavior) {
        if (raw != null && !raw.isBlank()) {
            try {
                return MinionCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                Logs.warn("分类 {} 无效，回退行为默认分类", raw);
            }
        }
        return switch (behavior) {
            case MINING -> MinionCategory.MINING;
            case FARMING -> MinionCategory.FARMING;
            case FORAGING -> MinionCategory.FORAGING;
            case FISHING -> MinionCategory.FISHING;
            case COMBAT -> MinionCategory.COMBAT;
            case RANCHING, GENERATOR -> MinionCategory.SPECIAL;
        };
    }

    private static Material defaultIcon(MinionBehavior behavior) {
        return switch (behavior) {
            case MINING -> Material.DIAMOND_PICKAXE;
            case FARMING -> Material.GOLDEN_HOE;
            case FORAGING -> Material.IRON_AXE;
            case FISHING -> Material.FISHING_ROD;
            case COMBAT -> Material.DIAMOND_SWORD;
            case RANCHING -> Material.SHEARS;
            case GENERATOR -> Material.STONE_PICKAXE;
        };
    }

    /** 解析分级解锁目标（targets-by-tier）：键 = 解锁等级数字，值 = 方块名列表；
     *  等级非数字/越界(2~max-level)或方块名非法时告警并跳过该项。 */
    private static Map<Integer, Set<Material>> parseTargetsByTier(ConfigurationSection s, String key) {
        Map<Integer, Set<Material>> out = new java.util.TreeMap<>();
        ConfigurationSection ts = s.getConfigurationSection("targets-by-tier");
        if (ts == null) {
            return out;
        }
        int maxLevel = s.getInt("max-level", 11);
        for (String k : ts.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(k.trim());
            } catch (NumberFormatException e) {
                Logs.warn("仆从类型 {} 的 targets-by-tier 含非数字等级键: {}，已忽略", key, k);
                continue;
            }
            if (level < 2 || level > maxLevel) {
                Logs.warn("仆从类型 {} 的 targets-by-tier 等级 {} 超出范围 (2~{})，已忽略", key, level, maxLevel);
                continue;
            }
            Set<Material> group = EnumSet.noneOf(Material.class);
            for (String name : ts.getStringList(k)) {
                try {
                    group.add(Material.valueOf(name.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    Logs.warn("未知目标方块: {} (仆从类型 {} 等级 {})", name, key, level);
                }
            }
            if (!group.isEmpty()) {
                out.put(level, group);
            }
        }
        return out;
    }

    /** 解析升级配方：优先读 upgrade-recipe 多材料段（键含冒号视为 CraftEngine 自定义物品 id）；
     *  为空时回退旧 upgrade-item + upgrade-cost 单材料（向后兼容）。 */
    private static Map<ItemRef, Long> parseRecipe(ConfigurationSection s, Material product, String key) {
        Map<ItemRef, Long> recipe = new LinkedHashMap<>();
        ConfigurationSection rs = s.getConfigurationSection("upgrade-recipe");
        if (rs != null) {
            for (String matName : rs.getKeys(false)) {
                ItemRef ref = ItemRef.parse(matName);
                if (ref == null) {
                    Logs.warn("仆从 {} 升级配方含未知物料: {}", key, matName);
                    continue;
                }
                long amount = rs.getLong(matName, 0);
                if (amount > 0) {
                    recipe.put(ref, amount);
                }
            }
        }
        if (recipe.isEmpty()) {
            Material item = parseMaterial(s.getString("upgrade-item"), product);
            recipe.put(new ItemRef.VanillaRef(item), Math.max(1, s.getLong("upgrade-cost", 64)));
        }
        return recipe;
    }

    /** 解析分阶配方覆盖：键 = 升级前等级数字，值 = 材料->数量（整行替代该级配方）。 */
    private static Map<Integer, Map<ItemRef, Long>> parseRecipeOverrides(ConfigurationSection s, String key) {
        Map<Integer, Map<ItemRef, Long>> out = new java.util.TreeMap<>();
        ConfigurationSection rs = s.getConfigurationSection("upgrade-recipe-at");
        if (rs == null) {
            return out;
        }
        for (String levelKey : rs.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(levelKey.trim());
            } catch (NumberFormatException e) {
                Logs.warn("仆从类型 {} 的 upgrade-recipe-at 含非数字等级键: {}，已忽略", key, levelKey);
                continue;
            }
            ConfigurationSection row = rs.getConfigurationSection(levelKey);
            if (row == null) {
                continue;
            }
            Map<ItemRef, Long> recipe = new LinkedHashMap<>();
            for (String matName : row.getKeys(false)) {
                ItemRef ref = ItemRef.parse(matName);
                if (ref == null) {
                    Logs.warn("仆从 {} 第 {} 级覆盖配方含未知物料: {}", key, level, matName);
                    continue;
                }
                long amount = row.getLong(matName, 0);
                if (amount > 0) {
                    recipe.put(ref, amount);
                }
            }
            if (!recipe.isEmpty()) {
                out.put(level, recipe);
            }
        }
        return out;
    }

    private static String str(FileConfiguration yaml, String path, String def) {
        return yaml.getString(path, def);
    }

    private static Material parseMaterial(String name, Material def) {
        if (name == null) {
            return def;
        }
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Logs.warn("未知物料名: {}，回退 {}", name, def);
            return def;
        }
    }

    /** 解析可空物料：null/未知名称返回 null（用于可选配置项，如稀有掉落）。 */
    private static Material parseMaterialNullable(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Logs.warn("未知物料名: {}（已忽略）", name);
            return null;
        }
    }
}
