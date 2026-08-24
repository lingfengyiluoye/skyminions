package com.hcs.minions.config;

/**
 * 离线收益结算配置（三道平衡锁见 docs/Hypixel玩法与排版提案.md）。
 *
 * @param enabled    总开关
 * @param maxHours   单次结算最长回溯时长（小时）
 * @param ratePercent 基础速度折扣百分比（100 = 离线吃满基础速度，仍无燃料/倍率加成）
 * @param minSeconds 低于此闲置秒数不结算（防频繁上下线噪音）
 */
public record OfflineProductionConfig(
        boolean enabled,
        int maxHours,
        int ratePercent,
        int minSeconds
) {
    public OfflineProductionConfig {
        maxHours = Math.max(1, maxHours);
        ratePercent = Math.max(0, Math.min(100, ratePercent));
        minSeconds = Math.max(0, minSeconds);
    }
}
