package com.hcs.minions.service;

import com.hcs.minions.config.OfflineProductionConfig;

/**
 * 离线结算窗口（把 {@code OfflineSettlement#settleOne} 的计时与幂等规则抽成纯函数）。
 *
 * <p>核心不变量：<b>同一段闲置时间不会被支付两次</b>。做法是「已支付指针」
 * {@code lastActiveEpochMs}——每次结算先原子推进指针，再按窗口补发。崩溃最多少发
 * （指针没推进成但物品已入仓的极小窗口），绝不重复支付。</p>
 *
 * <p>抽成纯函数后，这条不变量可以在没有 Bukkit 的情况下被测试钉死。</p>
 */
public final class OfflineWindow {

    private OfflineWindow() {
    }

    /**
     * 一次结算的时间账。
     *
     * @param idleMs    本次要结算的闲置时长（毫秒，已由调用方保证 ≥0）
     * @param cappedSec 实际补发所依据的秒数（已过 min-seconds 门槛、已按 max-hours 截断）
     * @param newPointer 结算后应写回的「已支付指针」（epoch ms）
     */
    public record Settlement(long idleMs, long cappedSec, long newPointer, boolean payable) {

        /** 是否需要产出（0 窗口/未达门槛都不产出，但指针仍要推进）。 */
        public boolean shouldProduce() {
            return payable && cappedSec > 0;
        }
    }

    /**
     * 计算一次结算。
     *
     * @param lastActive 结算前的已支付指针（epoch ms）
     * @param now        当前时间（epoch ms）
     * @param cfg        离线收益配置
     * @return 结算结果；{@code newPointer} 必须写回 Minion，否则同一窗口会被重复结算
     */
    public static Settlement settle(long lastActive, long now, OfflineProductionConfig cfg) {
        long idleMs = Math.max(0L, now - lastActive);
        long idleSec = idleMs / 1000L;
        if (idleSec < cfg.minSeconds()) {
            // 未达最低闲置秒数：不产出，但指针仍推进到 now——
            // 否则下次上线会用同一段旧指针重算，玩家反复上下线可把小窗口攒成大窗口
            return new Settlement(idleMs, 0L, now, false);
        }
        long cappedSec = Math.min(idleSec, cfg.maxHours() * 3600L);
        return new Settlement(idleMs, cappedSec, now, true);
    }

    /**
     * 连续两次结算同一时刻：第二次必须拿到 0 窗口（幂等性的数学表达）。
     * 这是「不会重复支付」的充要条件——测试直接断言它。
     */
    public static boolean isIdempotentAt(long lastActive, long now, OfflineProductionConfig cfg) {
        Settlement first = settle(lastActive, now, cfg);
        Settlement second = settle(first.newPointer(), now, cfg);
        return second.idleMs() == 0L && !second.shouldProduce();
    }
}
