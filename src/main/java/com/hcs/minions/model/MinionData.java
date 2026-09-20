package com.hcs.minions.model;

import java.util.UUID;

/**
 * 仆从的持久化快照（数据访问层与业务层之间的传输对象，全部为原始类型便于 SQL 映射）。
 * 运行时可变状态见 {@link Minion}。
 *
 * @param inventory 虚拟背包序列化字节（BLOB）
 */
public record MinionData(
        UUID id,
        UUID owner,
        String type,
        int level,
        String world,
        int x,
        int y,
        int z,
        long fuelTicks,
        /** 限时燃料当前加速倍率（与 fuelTicks 同寿命；1.0 = 无加成）。修复重启后 boost 归一。 */
        double fuelBoost,
        /** 产量倍率燃料当前值（催化剂类，1.0 = 无）。 */
        double multBoost,
        /** 产量倍率剩余 tick。 */
        long multTicks,
        /** 本轮限时燃料已装入的总 tick（GUI 进度条分母：剩余/总量 同维度）。 */
        long fuelTotalTicks,
        /** 本轮催化剂已装入的总 tick（GUI 进度条分母）。 */
        long multTotalTicks,
        long lastActiveEpochMs,
        String islandId,
        String upgrade1,
        String upgrade2,
        String upgrade3,
        String upgrade4,
        String skin,
        boolean autoSell,
        long totalProduced,
        double permanentBoost,
        byte[] inventory
) {
}
