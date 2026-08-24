package com.hcs.minions.service.hook;

import org.bukkit.Location;

import java.util.Optional;

/**
 * 空岛提供者抽象。放置仆从时调用一次 {@link #getIslandAt(Location)}，
 * 结果缓存在 SkyblockHook 中；工作循环只读缓存。
 */
public interface IslandProvider {

    Optional<IslandRef> getIslandAt(Location loc);
}
