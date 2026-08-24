package com.hcs.minions.service.hook;

import com.hcs.minions.model.Minion;
import com.hcs.minions.util.Logs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空岛联动门面（宽松策略）。
 *
 * <p>放置时缓存一次岛屿；工作循环读缓存做边界校验。为兼容普通世界（非空岛）与
 * 服务器重启，行为放宽：<b>无法确定岛屿时不限制工作</b>；重启后按需懒重缓存。
 * 只有在仆从明确位于某空岛内时，才校验其工作点仍在岛边界内。
 */
public final class SkyblockHook {

    private final IslandProvider provider;
    private final ConcurrentHashMap<String, IslandRef> islandCache = new ConcurrentHashMap<>();

    public SkyblockHook(JavaPlugin plugin) {
        if (Bukkit.getPluginManager().isPluginEnabled("SuperiorSkyblock2")) {
            this.provider = new SuperiorSkyblockProvider();
            Logs.info("已启用 SuperiorSkyblock2 联动");
        } else {
            this.provider = null;
            Logs.info("未检测到 SuperiorSkyblock2，空岛限制关闭");
        }
    }

    public boolean isEnabled() {
        return provider != null;
    }

    /** 放置时调用一次：解析并缓存岛屿，写入仆从 islandId。 */
    public void cacheIslandFor(Minion minion, Location loc) {
        if (provider == null) {
            return;
        }
        provider.getIslandAt(loc).ifPresent(island -> {
            minion.setIslandId(island.id().toString());
            islandCache.put(island.id().toString(), island);
        });
    }

    /** 放置校验：不强制必须在空岛（普通世界也可放置），仅在岛上时缓存边界。 */
    public boolean canPlaceAt(Location loc) {
        return true;
    }

    /** 工作校验：宽松 —— 非空岛不限制；空岛内校验边界；重启后懒重缓存。 */
    public boolean canWorkAt(Minion minion) {
        if (provider == null) {
            return true;
        }
        String islandId = minion.islandId();
        if (islandId == null) {
            return true; // 不在空岛，不限制
        }
        IslandRef island = resolveIsland(minion);
        if (island == null) {
            return true; // 岛屿可能已删除，不限制（宽松）
        }
        Location loc = minion.location().toLocation();
        return loc == null || island.isInside(loc);
    }

    /**
     * 团队使用校验：仆从主人本人直接放行；否则仅当仆从位于空岛且玩家是
     * 该岛成员（岛主/队友）时放行 —— 空岛团队成员可共同操作仆从。
     * 未装空岛插件或仆从不在岛上时退化为仅主人可用。
     */
    public boolean canUse(Minion minion, UUID playerId) {
        if (playerId.equals(minion.owner())) {
            return true;
        }
        if (provider == null || minion.islandId() == null) {
            return false;
        }
        IslandRef island = resolveIsland(minion);
        return island != null && island.isMember(playerId);
    }

    /** 解析仆从所属岛屿：优先读缓存，重启后按仆从位置懒重缓存；查不到返回 null。 */
    private IslandRef resolveIsland(Minion minion) {
        String islandId = minion.islandId();
        IslandRef island = islandCache.get(islandId);
        if (island != null) {
            return island;
        }
        Location loc = minion.location().toLocation();
        if (loc == null) {
            return null;
        }
        Optional<IslandRef> ref = provider.getIslandAt(loc);
        if (ref.isEmpty()) {
            return null;
        }
        islandCache.put(islandId, ref.get());
        return ref.get();
    }
}
