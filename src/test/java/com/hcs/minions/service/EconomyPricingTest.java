package com.hcs.minions.service;

import com.hcs.minions.config.CollectionConfig;
import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.DatabaseConfig;
import com.hcs.minions.config.EconomyConfig;
import com.hcs.minions.config.FuelEntry;
import com.hcs.minions.config.OfflineProductionConfig;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.config.RenderConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EconomyService#priceCents} 计价测试。
 *
 * <p>计价错一位，全服每笔售卖都错。用 BigDecimal 正是为了避免二进制浮点误差
 * （{@code 0.1 * 3 = 0.30000000000000004} 这类），这些测试把该精度钉死。</p>
 */
class EconomyPricingTest {

    /** 构造一份最小 PluginConfig：经济关闭（避免构造时触碰 Bukkit），只保留价格倍率。 */
    private static PluginConfig config(double priceMultiplier) {
        return new PluginConfig(
                new DatabaseConfig("sqlite", "minions.db", "127.0.0.1", 3306, "minions", "root", "", 4, false),
                new EconomyConfig(false, true, 400L, priceMultiplier),
                new RenderConfig(2.0f, 1.0f),
                20L, 48, 10, "texture", false,
                Map.of(),
                new CollectionConfig(true, new long[]{50, 100}, 100, Set.of(3), 5),
                new OfflineProductionConfig(true, 24, 100, 180, false),
                true, true, 48.0, 1, true,
                FuelEntry.Table.empty(),
                Map.of(), Map.of(), 9, Map.of(), java.util.List.of()
        );
    }

    private static EconomyService service(double priceMultiplier) {
        return new EconomyService(null, new ConfigProvider(null, config(priceMultiplier)), null);
    }

    @Test
    void unitPriceTimesUnitsInCents() {
        // 1.0 币/件 × 100 件 × 100(分) = 10000 分 = 100 币
        assertEquals(10_000L, service(1.0).priceCents(100, 1.0));
    }

    @Test
    void fractionalUnitPriceAvoidsFloatError() {
        // 0.1 币/件 × 3 件：二进制浮点会算出 0.30000000000000004，
        // BigDecimal 必须给出干净的 30 分
        assertEquals(30L, service(1.0).priceCents(3, 0.1));
        // 19.99 × 7 = 139.93 币 = 13993 分
        assertEquals(13_993L, service(1.0).priceCents(7, 19.99));
    }

    @Test
    void globalMultiplierScalesLinearly() {
        assertEquals(15_000L, service(1.5).priceCents(100, 1.0));
        assertEquals(5_000L, service(0.5).priceCents(100, 1.0));
    }

    @Test
    void zeroUnitsAndZeroPriceYieldZero() {
        assertEquals(0L, service(1.0).priceCents(0, 5.0));
        assertEquals(0L, service(1.0).priceCents(100, 0.0));
    }

    @Test
    void subCentAmountsTruncateNotRound() {
        // 0.001 币/件 × 1 件 = 0.1 分 → 取整为 0（分是最小单位，向下截断对服务器有利）
        assertEquals(0L, service(1.0).priceCents(1, 0.001));
        // 0.005 币/件 × 1 件 = 0.5 分 → 0（不是 1）
        assertEquals(0L, service(1.0).priceCents(1, 0.005));
    }

    @Test
    void largeAmountsStayExact() {
        // 附魔资源折算后件数极大：1792 件 × 12.5 币 × 100 = 2_240_000 分
        assertEquals(2_240_000L, service(1.0).priceCents(1792, 12.5));
    }
}
