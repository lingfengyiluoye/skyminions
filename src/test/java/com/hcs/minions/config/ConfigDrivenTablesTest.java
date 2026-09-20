package com.hcs.minions.config;

import org.bukkit.Material;
import com.hcs.minions.util.EnchantedResource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布版 config.yml 的配置驱动表解析回归测试。
 *
 * <p>这些表曾经硬编码在 Java 里，现在由 config.yml 驱动。若发布版文件与
 * {@code ConfigLoader} 的解析路径不一致（键名打错、段缺失、类型不对），
 * 插件会静默回退内置默认——服主改了文件却不生效。此测试守住发布版文件
 * 确实能被解析成预期的表。</p>
 */
class ConfigDrivenTablesTest {

    private static YamlConfiguration yaml;

    @BeforeAll
    static void loadShippedConfig() throws Exception {
        Path shipped = Path.of("src/main/resources/config.yml");
        assertTrue(Files.isRegularFile(shipped), "找不到发布版 config.yml");
        yaml = YamlConfiguration.loadConfiguration(
                new StringReader(Files.readString(shipped, StandardCharsets.UTF_8)));
        // 附魔资源 key 校验要查注册表：单测环境无 Bukkit（NamespacedKey 需要插件实例），
        // 只装载内置默认表即可（不涉及 PDC）
        EnchantedResource.ensureDefaults();
    }

    @Test
    void fuelsSectionParsesBothAxes() {
        FuelEntry.Table table =
                FuelEntry.parse(yaml.getList("fuels"), yaml.getList("enchanted-fuels"));
        assertFalse(table.byMaterial().isEmpty(), "fuels 段必须能解析出原版燃料");
        // 速度轴 / 永久 / 催化剂三类都要有
        assertTrue(table.byMaterial().values().stream().anyMatch(f -> !f.permanent() && f.boost() > 1.0),
                "应有速度轴燃料");
        assertTrue(table.byMaterial().values().stream().anyMatch(FuelEntry::permanent),
                "应有永久燃料");
        assertTrue(table.byMaterial().values().stream().anyMatch(FuelEntry::hasMultiplier),
                "应有催化剂（产量轴）");
        // 桶装燃料返还
        FuelEntry lava = table.byMaterial().get(Material.LAVA_BUCKET);
        assertNotNull(lava);
        assertEquals(Material.BUCKET, lava.returnsEmpty());
        // 附魔资源催化剂：必须挂在 collection 经济上
        assertEquals(3, table.byEnchanted().size(), "应有 3 个附魔资源催化剂");
        assertTrue(table.byEnchanted().containsKey("wheat"));
        assertTrue(table.byEnchanted().values().stream().allMatch(f -> f.multiplier() > 1.0),
                "附魔资源燃料必须走催化剂轴");
    }

    @Test
    void enchantedFuelsReferenceRegisteredResources() {
        FuelEntry.Table table =
                FuelEntry.parse(yaml.getList("fuels"), yaml.getList("enchanted-fuels"));
        // 交叉校验：附魔燃料引用的 key 必须出现在 enchanted-resources 注册表里
        // （运行时注册表由 EnchantedResource.reload 构建，单测环境无 Bukkit 无法初始化 PDC 键）
        java.util.Set<String> registered = new java.util.HashSet<>();
        for (Object o : yaml.getList("enchanted-resources")) {
            if (o instanceof Map<?, ?> m && m.get("key") != null) {
                registered.add(String.valueOf(m.get("key")));
            }
        }
        assertFalse(registered.isEmpty(), "enchanted-resources 段不能为空");
        for (String key : table.byEnchanted().keySet()) {
            assertTrue(registered.contains(key),
                    "附魔燃料引用了未注册的附魔资源: " + key);
        }
    }

    @Test
    void materialMapSectionsParse() {
        Map<Material, Material> smelt = readMap("auto-smelt");
        assertTrue(smelt.size() >= 20, "auto-smelt 至少 20 项，实际 " + smelt.size());
        assertEquals(Material.IRON_INGOT, smelt.get(Material.IRON_ORE));
        // 模拟采集产物是 RAW_* 形态：这三行决定铁/铜/金仆从的自动熔炼是否生效
        assertEquals(Material.IRON_INGOT, smelt.get(Material.RAW_IRON));
        assertEquals(Material.COPPER_INGOT, smelt.get(Material.RAW_COPPER));
        assertEquals(Material.GOLD_INGOT, smelt.get(Material.RAW_GOLD));

        assertEquals(9, yaml.getInt("compaction.ratio", 0), "默认压缩比 9:1");
        Map<Material, Material> compact = readMap("compaction.map");
        assertTrue(compact.size() >= 15, "compaction.map 至少 15 项，实际 " + compact.size());
        assertEquals(Material.COAL_BLOCK, compact.get(Material.COAL));

        Map<Material, Material> saplings = readMap("saplings");
        assertTrue(saplings.size() >= 10, "saplings 至少 10 项，实际 " + saplings.size());
        assertEquals(Material.OAK_SAPLING, saplings.get(Material.OAK_LOG));
        // 下界菌树：补种对应真菌
        assertEquals(Material.CRIMSON_FUNGUS, saplings.get(Material.CRIMSON_STEM));
    }

    @Test
    void enchantedResourcesSectionMatchesRegistrySize() {
        List<?> raw = yaml.getList("enchanted-resources");
        assertNotNull(raw, "config.yml 必须有 enchanted-resources 段");
        assertTrue(raw.size() >= 50, "附魔资源注册表至少 50 项，实际 " + raw.size());
        // 每项都能解析且 key 唯一
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Object o : raw) {
            EnchantedResourceDef def = EnchantedResourceDef.parse(o);
            assertNotNull(def, "存在无法解析的附魔资源配置: " + o);
            assertTrue(keys.add(def.key()), "附魔资源 key 重复: " + def.key());
        }
        // 末影珍珠保持 32:1（与其他 160:1 不同）
        boolean hasPearl32 = raw.stream()
                .filter(o -> o instanceof Map<?, ?> m && "ender_pearl".equals(m.get("key")))
                .anyMatch(o -> ((Map<?, ?>) o).get("ratio") instanceof Number n && n.intValue() == 32);
        assertTrue(hasPearl32, "末影珍珠应为 32:1");
    }

    @Test
    void offlineProductionFuelSwitchDefaultsToOff() {
        // 默认 false：离线不吃加速就不该烧燃料（修复"白烧"）
        assertFalse(yaml.getBoolean("offline-production.burn-fuel-offline", true),
                "burn-fuel-offline 默认必须为 false");
    }

    /** 按 ConfigLoader 同款方式解析「输入 -> 产物」映射段。 */
    private static Map<Material, Material> readMap(String path) {
        org.bukkit.configuration.ConfigurationSection section = yaml.getConfigurationSection(path);
        assertNotNull(section, "config.yml 缺少段: " + path);
        Map<Material, Material> out = new java.util.LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            try {
                out.put(Material.valueOf(key), Material.valueOf(section.getString(key)));
            } catch (IllegalArgumentException e) {
                throw new AssertionError("物料映射无法识别: " + key + " -> " + section.getString(key));
            }
        }
        return out;
    }
}
