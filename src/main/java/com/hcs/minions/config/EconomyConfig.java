package com.hcs.minions.config;

/**
 * 经济/自动售卖配置。金额在内部一律用 long（分）或 BigDecimal 运算，
 * 仅在 Vault 边界转换为 double。
 */
public record EconomyConfig(
        boolean enabled,
        boolean autoSellOnFull,
        long sellIntervalTicks,
        double priceMultiplier
) {
    public EconomyConfig {
        sellIntervalTicks = Math.max(4L, sellIntervalTicks);
        priceMultiplier = Double.isFinite(priceMultiplier) && priceMultiplier > 0
                ? priceMultiplier : 1.0;
    }
}
