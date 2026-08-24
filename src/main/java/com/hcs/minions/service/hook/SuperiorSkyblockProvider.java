package com.hcs.minions.service.hook;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.hcs.minions.util.Logs;
import org.bukkit.Location;

import java.util.Optional;
import java.util.UUID;

/**
 * SuperiorSkyblock2 桥接实现。本类只在插件检测到 SSB2 已启用时才被加载，
 * 因此即使目标服务器未安装 SSB2 也不会触发 NoClassDefFoundError。
 */
public final class SuperiorSkyblockProvider implements IslandProvider {

    @Override
    public Optional<IslandRef> getIslandAt(Location loc) {
        try {
            Island island = SuperiorSkyblockAPI.getIslandAt(loc);
            return island == null ? Optional.empty() : Optional.of(new SuperiorIslandRef(island));
        } catch (Exception e) {
            // 优雅降级：API 异常时视为无岛屿，绝不吞异常不记录
            Logs.error("SuperiorSkyblock2 查询岛屿失败", e);
            return Optional.empty();
        }
    }

    private record SuperiorIslandRef(Island island) implements IslandRef {

        @Override
        public UUID id() {
            return island.getUniqueId();
        }

        @Override
        public boolean isInside(Location loc) {
            return island.isInside(loc);
        }

        @Override
        public boolean isOwner(UUID playerId) {
            SuperiorPlayer owner = island.getOwner();
            return owner != null && playerId.equals(owner.getUniqueId());
        }

        @Override
        public boolean isMember(UUID playerId) {
            try {
                SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(playerId);
                return sp != null && island.isMember(sp);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
