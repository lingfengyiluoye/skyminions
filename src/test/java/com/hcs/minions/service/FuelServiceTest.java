package com.hcs.minions.service;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FuelService} 燃料注册表与属性测试。 */
class FuelServiceTest {

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
        FuelService.FuelValue coal = FuelService.valueOf(Material.COAL);
        assertNotNull(coal);
        assertFalse(coal.permanent());
        assertTrue(coal.durationTicks() > 0);
        assertTrue(coal.boost() > 1.0);
    }

    @Test
    void permanentFuelHasZeroDuration() {
        FuelService.FuelValue magma = FuelService.valueOf(Material.MAGMA_CREAM);
        assertNotNull(magma);
        assertTrue(magma.permanent());
        assertEquals(0L, magma.durationTicks());
        assertTrue(magma.boost() > 1.0);
    }

    @Test
    void valueOfReturnsNullForUnknown() {
        assertNull(FuelService.valueOf(null));
        assertNull(FuelService.valueOf(Material.DIRT));
    }

    @Test
    void allReturnsOrderedRegistry() {
        Map<Material, FuelService.FuelValue> all = FuelService.all();
        assertFalse(all.isEmpty());
        // 注册表应包含基础、高级和永久燃料
        assertTrue(all.containsKey(Material.COAL), "应包含基础燃料煤炭");
        assertTrue(all.containsKey(Material.LAVA_BUCKET), "应包含高级燃料岩浆桶");
        assertTrue(all.containsKey(Material.GLOWSTONE_DUST), "应包含永久燃料荧石粉");
    }

    @Test
    void registryIsUnmodifiable() {
        Map<Material, FuelService.FuelValue> all = FuelService.all();
        assertThrows(UnsupportedOperationException.class,
                () -> all.put(Material.DIRT, FuelService.FuelValue.timed(100, 1.0)));
    }
}
