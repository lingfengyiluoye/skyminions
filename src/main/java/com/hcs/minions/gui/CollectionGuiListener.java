package com.hcs.minions.gui;

import com.hcs.minions.model.MinionCategory;
import com.hcs.minions.model.MinionType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Map;

/**
 * 图鉴 GUI 交互：顶行分类过滤、底行翻页、卡片点击查看升级配方。
 * 翻页/过滤通过 {@link CollectionGui#open} 重建界面实现，无共享可变状态。
 */
public final class CollectionGuiListener implements Listener {

    /** 顶行分类过滤按钮：槽位 -> 分类（槽 0 为「全部」，null 值不能进 Map，单独处理）。 */
    private static final Map<Integer, MinionCategory> FILTER_SLOTS = Map.of(
            1, MinionCategory.MINING,
            2, MinionCategory.FARMING,
            3, MinionCategory.FORAGING,
            4, MinionCategory.COMBAT,
            5, MinionCategory.FISHING,
            6, MinionCategory.SPECIAL
    );

    private static final int ALL_SLOT = 0;

    private static final int PREV_SLOT = 48;
    private static final int NEXT_SLOT = 50;
    private static final int CLOSE_SLOT = 8;

    private final CollectionGui gui;

    public CollectionGuiListener(CollectionGui gui) {
        this.gui = gui;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CollectionGui.CollectionHolder holder)) {
            return;
        }
        event.setCancelled(true); // 图鉴为纯展示界面，禁止取放
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot >= event.getInventory().getSize()) {
            return; // 玩家背包区不拦截（物品仍不可放入，因已 cancel）
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == ALL_SLOT) {
            gui.open(player, null, 0);
            return;
        }
        if (FILTER_SLOTS.containsKey(slot)) {
            gui.open(player, FILTER_SLOTS.get(slot), 0);
            return;
        }
        if (slot == PREV_SLOT) {
            gui.open(player, holder.filter(), holder.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            gui.open(player, holder.filter(), holder.page() + 1);
            return;
        }
        if (slot >= 9 && slot <= 44) {
            cardClick(player, holder, slot);
        }
    }

    /** 点击卡片：聊天栏展示该类型的下一级升级配方（未解锁卡片无响应）。 */
    private void cardClick(Player player, CollectionGui.CollectionHolder holder, int slot) {
        int index = holder.page() * 36 + (slot - 9);
        MinionType[] types = MinionType.values();
        // 与 CollectionGui.visibleTypes 相同的过滤顺序还原被点击的类型
        int visible = -1;
        for (MinionType type : types) {
            if (holder.filter() != null && type.category() != holder.filter()) {
                continue;
            }
            visible++;
            if (visible == index) {
                gui.sendRecipeDetails(player, type);
                return;
            }
        }
    }
}
