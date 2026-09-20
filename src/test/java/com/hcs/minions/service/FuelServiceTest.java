package com.hcs.minions.service;

import com.hcs.minions.config.FuelEntry;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FuelService} 燃料注册表与属性测试（内置默认表）。 */
class FuelServiceTest {

    @BeforeEach
    void resetToBuiltins() {
        FuelService.reload(null); // null -> 回退内置默认表
    }

    @Test
    void knownMaterialsAreRecognizedAsFuel() {
        assertTrue(FuelService.isFuel(Material.COAL));
        assertTrue(FuelService.isFuel(Material.LAVA_BUCKET));
        assertTrue(FuelService.isFuel(Material.BLAZE_ROD));
        assertTrue(FuelService.isFuel(Material.MAGMA_CREAM));
    }

    @Test
    void unknownMaterialsAreNotFuel() {
        assertFalse(FuelService.isFuel(Material.DIAMOND));
        assertFalse(FuelService.isFuel(Material.STONE));
        assertFalse(FuelService.isFuel(null));
    }

    @Test
    void timedFuelHasDurationAndBoost() {
        FuelEntry coal = FuelService.valueOf(Material.COAL);
        assertNotNull(coal);
        assertFalse(coal.permanent());
        assertTrue(coal.durationTicks() > 0);
        assertTrue(coal.boost() > 1.0);
    }

    @Test
    void permanentFuelHasZeroDuration() {
        FuelEntry magma = FuelService.valueOf(Material.MAGMA_CREAM);
        assertNotNull(magma);
        assertTrue(magma.permanent());
        assertEquals(0L, magma.durationTicks());
        assertTrue(magma.boost() > 1.0);
    }

    @Test
    void valueOfReturnsNullForUnknown() {
        assertNull(FuelService.valueOf((Material) null));
        assertNull(FuelService.valueOf(Material.DIRT));
    }

    @Test
    void lavaBucketReturnsEmptyContainer() {
        FuelEntry lava = FuelService.valueOf(Material.LAVA_BUCKET);
        assertNotNull(lava);
        assertTrue(lava.hasEmptyContainer(), "岩浆桶应配置返还空桶");
        assertEquals(Material.BUCKET, lava.returnsEmpty());
    }

    @Test
    void catalystsCarryMultiplierNotSpeed() {
        FuelEntry catalyst = FuelService.valueOf(Material.BLAZE_POWDER);
        assertNotNull(catalyst);
        assertTrue(catalyst.hasMultiplier(), "烈焰粉应为催化剂（产量倍率轴）");
        assertEquals(2.0, catalyst.multiplier());
        assertEquals(1.0, catalyst.boost(), "催化剂不加速度");
    }

    @Test
    void allReturnsOrderedRegistryIncludingEnchanted() {
        List<FuelEntry> all = FuelService.all();
        assertFalse(all.isEmpty());
        // 原版燃料：基础/高级/永久三类都在
        assertTrue(all.stream().anyMatch(e -> e.icon() == Material.COAL), "应包含基础燃料煤炭");
        assertTrue(all.stream().anyMatch(e -> e.icon() == Material.LAVA_BUCKET), "应包含高级燃料岩浆桶");
        assertTrue(all.stream().anyMatch(e -> e.icon() == Material.GLOWSTONE_DUST), "应包含永久燃料荧石粉");
        // 附魔资源催化剂：沉淀在 collection 经济里（对齐 Hypixel 附魔面包）
        assertTrue(all.stream().anyMatch(e -> "wheat".equals(e.enchantedKey())),
                "应包含附魔小麦催化剂");
        assertTrue(all.stream().anyMatch(e -> e.enchantedKey() != null && e.hasMultiplier()),
                "附魔资源燃料必须走催化剂轴（倍率 >1）");
    }

    @Test
    void reloadFallsBackToBuiltinsOnEmptyTable() {
        FuelService.reload(FuelEntry.Table.empty());
        assertTrue(FuelService.isFuel(Material.COAL), "空配置必须回退内置默认而不是把插件置成无燃料");
    }
}
