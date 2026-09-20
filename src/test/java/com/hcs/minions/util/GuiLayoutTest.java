package com.hcs.minions.util;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GuiLayout} 布局配置解析测试：覆盖默认回退、区间语法、越界/非法值兜底。 */
class GuiLayoutTest {

    @BeforeEach
    void reset() {
        GuiLayout.load(YamlConfiguration.loadConfiguration(new StringReader("")));
    }

    @Test
    void emptyConfigUsesBuiltInDefaults() {
        assertEquals(49, GuiLayout.slot("storage.collect.slot"));
        assertEquals(18, GuiLayout.slot("fuel-gui.status.slot"));
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, GuiLayout.slots("collection.filter.slots"));
        assertEquals(Material.BARRIER, GuiLayout.material("collection.locked.material"));
    }

    @Test
    void slotOverrideApplied() {
        GuiLayout.load(YamlConfiguration.loadConfiguration(
                new StringReader("layout:\n  storage:\n    upgrade:\n      slot: 6\n")));
        assertEquals(6, GuiLayout.slot("storage.upgrade.slot"));
        // 未覆盖的键仍用默认
        assertEquals(49, GuiLayout.slot("storage.collect.slot"));
    }

    @Test
    void rangeSyntaxExpanded() {
        GuiLayout.load(YamlConfiguration.loadConfiguration(
                new StringReader("layout:\n  storage:\n    slots: [9-11, 20]\n")));
        assertArrayEquals(new int[]{9, 10, 11, 20}, GuiLayout.slots("storage.slots"));
    }

    @Test
    void outOfRangeSlotFallsBack() {
        // 仓库界面 54 格：99 越界回退默认 49
        GuiLayout.load(YamlConfiguration.loadConfiguration(
                new StringReader("layout:\n  storage:\n    collect:\n      slot: 99\n")));
        assertEquals(49, GuiLayout.slot("storage.collect.slot"));
    }

    @Test
    void fuelGuiUses27GridBoundary() {
        // 燃料选择界面 27 格：26 合法、30 越界回退（状态卡默认 18，避开两行选项区）
        GuiLayout.load(YamlConfiguration.loadConfiguration(new StringReader(
                "layout:\n  fuel-gui:\n    close:\n      slot: 26\n    status:\n      slot: 30\n")));
        assertEquals(26, GuiLayout.slot("fuel-gui.close.slot"));
        assertEquals(18, GuiLayout.slot("fuel-gui.status.slot"));
    }

    @Test
    void invalidMaterialFallsBack() {
        GuiLayout.load(YamlConfiguration.loadConfiguration(
                new StringReader("layout:\n  storage:\n    pickup:\n      material: NOT_A_MATERIAL\n")));
        assertEquals(Material.ARMOR_STAND, GuiLayout.material("storage.pickup.material"));
    }

    @Test
    void validMaterialOverrideApplied() {
        GuiLayout.load(YamlConfiguration.loadConfiguration(
                new StringReader("layout:\n  collection:\n    icon:\n      mining: IRON_PICKAXE\n")));
        assertEquals(Material.IRON_PICKAXE, GuiLayout.material("collection.icon.mining"));
    }

    @Test
    void allInvalidSlotsFallBackToDefault() {
        GuiLayout.load(YamlConfiguration.loadConfiguration(
                new StringReader("layout:\n  fuel-gui:\n    option:\n      slots: [99, -3]\n")));
        // 默认两行共 14 个选项槽（≥ 11 种燃料，含催化剂类）
        assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25},
                GuiLayout.slots("fuel-gui.option.slots"));
    }

    @Test
    void materialsGuiDefaultsRegistered() {
        // 升级材料总览/预览的布局键必须全部登记在内置默认中（删除 gui.yml 键即回退）
        assertEquals(28, GuiLayout.slots("materials-gui.card.slots").length);
        assertEquals(9, GuiLayout.slots("materials-gui.detail.grid.slots").length);
        assertEquals(49, GuiLayout.slot("materials-gui.close.slot"));
        assertEquals(25, GuiLayout.slot("materials-gui.detail.result.slot"));
        assertEquals(Material.KNOWLEDGE_BOOK, GuiLayout.material("materials-gui.detail.info.material"));
        // 未登记的键不抛异常，回退空/0
        assertArrayEquals(new int[0], GuiLayout.slots("materials-gui.no-such-key"));
        assertEquals(0, GuiLayout.slot("materials-gui.no-such-key"));
    }

    @Test
    void guideListDecorSlotsRegistered() {
        // guide-list.decor.slots 此前漏登记：服主删除该键后装饰会静默消失
        assertTrue(GuiLayout.slots("guide-list.decor.slots").length > 0);
    }

    @Test
    void quotedNumericSlotStillParsed() {
        // 服主把整数写成带引号字符串（"5"）时不应静默回退，而是按 5 生效
        GuiLayout.load(YamlConfiguration.loadConfiguration(
                new StringReader("layout:\n  storage:\n    head:\n      slot: \"4\"\n")));
        assertEquals(4, GuiLayout.slot("storage.head.slot"));
    }

    @Test
    void shippedGuiYmlLayoutParses() throws Exception {
        // 发布版 gui.yml 的 layout 段必须能被完整解析：新键（materials-gui.*）缺漏时
        // 会静默走内置默认，玩家/服主改了文件却不生效，故在此守住。
        // 走 Reader 路径（File 变体会请求 Bukkit.getLogger()，单测环境无服务端）
        java.io.File shipped = new java.io.File("src/main/resources/gui.yml");
        assertTrue(shipped.isFile(), "找不到发布版 gui.yml");
        String content = java.nio.file.Files.readString(shipped.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        GuiLayout.load(YamlConfiguration.loadConfiguration(new StringReader(content)));
        assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25},
                GuiLayout.slots("fuel-gui.option.slots"));
        assertEquals(18, GuiLayout.slot("fuel-gui.status.slot"));
        assertEquals(28, GuiLayout.slots("materials-gui.card.slots").length);
        assertEquals(25, GuiLayout.slot("materials-gui.detail.result.slot"));
        assertEquals(9, GuiLayout.slots("materials-gui.detail.grid.slots").length);
        assertEquals(Material.KNOWLEDGE_BOOK, GuiLayout.material("materials-gui.detail.info.material"));
    }
}
