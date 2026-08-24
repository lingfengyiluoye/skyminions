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
        long xp,
        String world,
        int x,
        int y,
        int z,
        long fuelTicks,
        long lastActiveEpochMs,
        String islandId,
        String upgrade1,
        String upgrade2,
        String skin,
        byte[] inventory
) {
}
