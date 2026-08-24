package com.hcs.minions.event;

import com.hcs.minions.model.Minion;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 仆从放置事件（自定义 Bukkit Event，模块间解耦的桥梁）。
 */
public class MinionPlacedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Minion minion;
    private final Player player;

    public MinionPlacedEvent(Minion minion, Player player) {
        this.minion = minion;
        this.player = player;
    }

    public Minion minion() {
        return minion;
    }

    public Player player() {
        return player;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
