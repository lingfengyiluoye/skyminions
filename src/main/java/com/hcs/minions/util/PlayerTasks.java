package com.hcs.minions.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Consumer;

/** Folia 玩家实体线程调度出口。玩家背包、消息、音效和标题都应通过此处投递。 */
public final class PlayerTasks {

    private PlayerTasks() {
    }

    public static void run(JavaPlugin plugin, UUID playerId, Consumer<Player> action) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        run(plugin, player, action);
    }

    public static void run(JavaPlugin plugin, Player player, Consumer<Player> action) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                action.accept(player);
            }
        }, null);
    }
}
