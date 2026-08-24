package com.hcs.minions.listener;

import com.hcs.minions.model.Minion;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.OfflineRewardService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;

/**
 * 玩家上线监听：批量结算离线收益（一次性给予，绝不逐 tick 回放）。
 */
public final class PlayerJoinListener implements Listener {

    private final OfflineRewardService offline;
    private final MinionManager manager;

    public PlayerJoinListener(OfflineRewardService offline, MinionManager manager) {
        this.offline = offline;
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<Minion> owned = manager.all().stream()
                .filter(m -> player.getUniqueId().equals(m.owner()))
                .toList();
        offline.grantOnJoin(player, owned);
    }
}
