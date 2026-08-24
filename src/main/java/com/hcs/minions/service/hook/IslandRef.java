package com.hcs.minions.service.hook;

import org.bukkit.Location;

import java.util.UUID;

/**
 * 岛屿引用抽象（与 SuperiorSkyblock2 解耦）。缓存的是本对象，工作循环中只做
 * 内存内边界校验，绝不回源查询 API。
 */
public interface IslandRef {

    UUID id();

    /** 该位置是否位于岛屿边界内。 */
    boolean isInside(Location loc);

    /** 玩家是否为岛屿主人。 */
    boolean isOwner(UUID playerId);

    /** 玩家是否为岛屿成员（含岛主与全部队友）。 */
    boolean isMember(UUID playerId);
}
