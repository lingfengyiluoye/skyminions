package com.hcs.minions.config;

import com.hcs.minions.util.ItemRef;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ConfigLoader} 配方容量体检纯函数测试。
 *
 * <p>合成格只有固定数量（默认 4×4=16 格），配方整叠装箱后若超格，玩家
 * 「看得见配方却摆不齐」——启动期必须能算准所需格数并告警。</p>
 */
class ConfigLoaderCapacityTest {

    private static Map<ItemRef, Long> recipe(Object... kv) {
        Map<ItemRef, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(new ItemRef.VanillaRef((Material) kv[i]), ((Number) kv[i + 1]).longValue());
        }
        return m;
    }

    @Test
    void stacksRoundUpPerMaterial() {
        // WHEAT 512 = 8 整叠；GOLDEN_CARROT 32、DIAMOND 5 各占 1 格；需本体再 +1
        assertEquals(11, ConfigLoader.requiredGridSlots(
                recipe(Material.WHEAT, 512, Material.GOLDEN_CARROT, 32, Material.DIAMOND, 5), true));
    }

    @Test
    void bodyOptionalTakesNoSlotWhenDisabled() {
        // 关闭「需要上一级本体」时不多占格
        assertEquals(10, ConfigLoader.requiredGridSlots(
                recipe(Material.WHEAT, 512, Material.GOLDEN_CARROT, 32, Material.DIAMOND, 5), false));
    }

    @Test
    void partialStackStillOccupiesOneSlot() {
        // 65 个圆石 = 1 整叠 + 1 零头 → 2 格
        assertEquals(2, ConfigLoader.requiredGridSlots(recipe(Material.COBBLESTONE, 65), false));
    }

    @Test
    void emptyRecipeOnlyNeedsBodySlot() {
        assertEquals(1, ConfigLoader.requiredGridSlots(recipe(), true));
        assertEquals(0, ConfigLoader.requiredGridSlots(recipe(), false));
    }
}
