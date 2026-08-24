package com.hcs.minions.util;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link ItemRef#parse(String)} 配置键解析测试（纯函数，CraftEngine 缺席时安全降级）。 */
class ItemRefTest {

    @Test
    void parsesVanillaMaterialKey() {
        ItemRef ref = ItemRef.parse("cobblestone");
        assertInstanceOf(ItemRef.VanillaRef.class, ref);
        assertEquals(Material.COBBLESTONE, ((ItemRef.VanillaRef) ref).material());
        assertEquals("COBBLESTONE", ref.configKey());
    }

    @Test
    void parsesColonKeyAsCustomItem() {
        // 含冒号 → CraftEngine 自定义物品 id（原样保留大小写）
        ItemRef ref = ItemRef.parse("craftengine:my_item");
        assertInstanceOf(ItemRef.CustomRef.class, ref);
        assertEquals("craftengine:my_item", ((ItemRef.CustomRef) ref).id());
        assertEquals("my_item", ref.displayName());
    }

    @Test
    void unknownVanillaKeyReturnsNull() {
        assertNull(ItemRef.parse("NOT_A_MATERIAL"));
        assertNull(ItemRef.parse(""));
        assertNull(ItemRef.parse(null));
    }

    @Test
    void customRefWithoutCraftEngineDegradesSafely() {
        // 类路径无 CraftEngine：icon 回退 BARRIER，prototype 返回 null，不抛异常
        ItemRef.CustomRef ref = (ItemRef.CustomRef) ItemRef.parse("testns:ghost_item");
        assertNull(ref.prototype());
        assertEquals(Material.BARRIER, ref.icon());
    }

    @Test
    void refsEqualByContent() {
        assertEquals(new ItemRef.VanillaRef(Material.COAL), ItemRef.parse("COAL"));
        assertEquals(new ItemRef.CustomRef("a:b"), ItemRef.parse("a:b"));
    }
}
