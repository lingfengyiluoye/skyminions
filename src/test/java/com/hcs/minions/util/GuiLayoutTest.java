package com.hcs.minions.util;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link GuiLayout} 布局配置解析测试：覆盖默认回退、区间语法、越界/非法值兜底。 */
class GuiLayoutTest {

    @BeforeEach
    void reset() {
        GuiLayout.load(YamlConfiguration.loadConfiguration(new StringReader("")));
    }

    @Test
    void emptyConfigUsesBuiltInDefaults() {
        assertEquals(49, GuiLayout.slot("storage.collect.slot"));
        assertEquals(22, GuiLayout.slot("fuel-gui.status.slot"));
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
        // 燃料选择界面 27 格：26 合法、30 越界回退
        GuiLayout.load(YamlConfiguration.loadConfiguration(new StringReader(
                "layout:\n  fuel-gui:\n    close:\n      slot: 26\n    status:\n      slot: 30\n")));
        assertEquals(26, GuiLayout.slot("fuel-gui.close.slot"));
        assertEquals(22, GuiLayout.slot("fuel-gui.status.slot"));
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
        assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15, 16}, GuiLayout.slots("fuel-gui.option.slots"));
    }
}
