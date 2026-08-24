package com.hcs.minions.gui;

import com.hcs.minions.model.MinionCategory;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.util.GuiLayout;
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

    /** 顶行分类过滤按钮：槽位 -> 分类（槽位由 gui.yml layout 段配置，按枚举顺序对应）。 */
    private static Map<Integer, MinionCategory> filterSlots() {
        int[] slots = GuiLayout.slots("collection.filter.slots");
        MinionCategory[] cats = MinionCategory.values();
        Map<Integer, MinionCategory> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < Math.min(slots.length, cats.length); i++) {
            out.put(slots[i], cats[i]);
        }
        return out;
    }

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
        if (slot == GuiLayout.slot("collection.close.slot")) {
            player.closeInventory();
            return;
        }
        if (slot == GuiLayout.slot("collection.all.slot")) {
            gui.open(player, null, 0);
            return;
        }
        Map<Integer, MinionCategory> filters = filterSlots();
        if (filters.containsKey(slot)) {
            gui.open(player, filters.get(slot), 0);
            return;
        }
        if (slot == GuiLayout.slot("collection.prev.slot")) {
            gui.open(player, holder.filter(), holder.page() - 1);
            return;
        }
        if (slot == GuiLayout.slot("collection.next.slot")) {
            gui.open(player, holder.filter(), holder.page() + 1);
            return;
        }
        cardClick(player, holder, slot);
    }

    /** 点击卡片：聊天栏展示该类型的下一级升级配方（未解锁卡片无响应）。 */
    private void cardClick(Player player, CollectionGui.CollectionHolder holder, int slot) {
        int[] cards = GuiLayout.slots("collection.card.slots");
        int inPage = -1;
        for (int i = 0; i < cards.length; i++) {
            if (cards[i] == slot) {
                inPage = i;
                break;
            }
        }
        if (inPage < 0) {
            return; // 非卡片区槽位
        }
        int index = holder.page() * cards.length + inPage;
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
