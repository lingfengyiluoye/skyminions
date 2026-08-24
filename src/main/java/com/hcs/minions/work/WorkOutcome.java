package com.hcs.minions.work;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 一次策略执行的结果。
 *
 * @param worked 是否实际工作（false 表示本次无目标，调度器据此不推进冷却/不扣燃料）
 * @param xp     本次获得的经验（由 Manager 统一结算升级）
 * @param drops  采集到的掉落物（ItemStack 为本次创建、尚未进入虚拟背包，移交时需深拷贝）
 */
public record WorkOutcome(boolean worked, long xp, List<ItemStack> drops) {

    public static final WorkOutcome IDLE = new WorkOutcome(false, 0, List.of());

    public WorkOutcome {
        drops = List.copyOf(drops);
    }
}
