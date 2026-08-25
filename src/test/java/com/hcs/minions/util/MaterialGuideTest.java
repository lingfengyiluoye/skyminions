package com.hcs.minions.util;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MaterialGuide} 回归测试。
 * 重点防复发：配方形状允许 null 空格（历史事故：List.of 不接受 null，
 * 类初始化 NPE 经 /minion materials 触发 ExceptionInInitializerError 崩服）。
 */
class MaterialGuideTest {

    @Test
    void twoByTwoRecipeKeepsNullEmptyCells() {
        List<Material> grid = MaterialGuide.gridOf(Material.QUARTZ_BLOCK);
        assertEquals(9, grid.size(), "网格恒为 9 格");
        assertEquals(Material.QUARTZ, grid.get(0));
        assertEquals(Material.QUARTZ, grid.get(1));
        assertNull(grid.get(2), "空格应为 null");
        assertEquals(Material.QUARTZ, grid.get(3));
        assertEquals(Material.QUARTZ, grid.get(4));
        assertNull(grid.get(5));
    }

    @Test
    void shapedRecipeSupportsNullCells() {
        List<Material> grid = MaterialGuide.gridOf(Material.ANVIL);
        assertEquals(9, grid.size());
        assertEquals(Material.IRON_BLOCK, grid.get(0));
        assertNull(grid.get(3), "铁砧中排两侧为空格 null");
        assertEquals(Material.IRON_INGOT, grid.get(4));
    }

    @Test
    void everyCraftableEntryHasNineCellGrid() {
        for (MaterialGuide.Guide g : MaterialGuide.all()) {
            if (g.craftable()) {
                assertEquals(9, g.grid().size(), g.vanillaName() + " 的形状必须为 9 格");
            }
        }
    }

    @Test
    void nonCraftableEntriesHaveEmptyGrid() {
        assertTrue(MaterialGuide.gridOf(Material.CRYING_OBSIDIAN).isEmpty(),
                "不可合成物不应有摆法");
    }

    @Test
    void wrapHoverProducesMiniMessageTagForKnownMaterial() {
        String out = MaterialGuide.wrapHover(Material.HAY_BLOCK, "· 测试行");
        assertTrue(out.startsWith("<hover:show_text:'"), "应包裹 hover 标签");
        assertTrue(out.contains("干草块"));
        assertTrue(out.endsWith("</hover>"));
        // 不抛 MiniMessage 解析异常即视为合法
        assertDoesNotThrow(() ->
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(out));
    }

    @Test
    void wrapHoverPassthroughForUnknownMaterial() {
        String out = MaterialGuide.wrapHover(Material.DIRT, "· 泥土");
        assertEquals("· 泥土", out);
    }
}
