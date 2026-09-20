package com.hcs.minions.event;

import com.hcs.minions.model.Minion;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 仆从收取事件（GUI 点击“收取”或交互时触发）。
 */
public class MinionCollectEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Minion minion;
    private final Player player;
    private final List<ItemStack> collected;

    public MinionCollectEvent(Minion minion, Player player, List<ItemStack> collected) {
        this.minion = minion;
        this.player = player;
        // 防御性拷贝：调用方（GUI）在事件后还会继续使用原列表，
        // 监听器不得通过改这个列表反向影响收取流程（对齐 WorkOutcome 的口径）
        this.collected = List.copyOf(collected);
    }

    public Minion minion() {
        return minion;
    }

    public Player player() {
        return player;
    }

    public List<ItemStack> collected() {
        return collected;
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
