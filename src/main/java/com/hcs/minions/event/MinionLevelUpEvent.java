package com.hcs.minions.event;

import com.hcs.minions.model.Minion;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 仆从升级事件。
 */
public class MinionLevelUpEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Minion minion;
    private final int newLevel;

    public MinionLevelUpEvent(Minion minion, int newLevel) {
        this.minion = minion;
        this.newLevel = newLevel;
    }

    public Minion minion() {
        return minion;
    }

    public int newLevel() {
        return newLevel;
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
