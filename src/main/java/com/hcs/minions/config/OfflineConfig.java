package com.hcs.minions.config;

/**
 * 离线收益配置。
 *
 * @param maxHours       最大计算时长上限（防止无上限回放，超出截断）
 * @param rateMultiplier 离线效率折扣
 */
public record OfflineConfig(
        boolean enabled,
        int maxHours,
        double rateMultiplier
) {
}
