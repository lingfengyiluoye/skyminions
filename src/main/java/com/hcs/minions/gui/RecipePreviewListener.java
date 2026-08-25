package com.hcs.minions.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 合成预览 GUI 交互：纯展示视图。
 *
 * <p>防刷规则：
 * <ul>
 *   <li>整个视图（含玩家背包区）所有点击/拖拽一律取消——网格与成品均为展示克隆；</li>
 *   <li>仅 ◀ ▶（切换材料）与 关闭 三个按钮有行为；</li>
 *   <li>holder 绑定打开者，非本人操作直接关闭界面。</li>
 * </ul>
 */
public final class RecipePreviewListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RecipePreviewGui.PreviewHolder holder)) {
            return;
        }
        // 纯展示：一切点击取消（含 shift/double-click/数字键交换等所有变体）
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.getUniqueId().equals(holder.playerId())) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        int size = event.getInventory().getSize();
        if (slot == RecipePreviewGui.closeSlot()) {
            player.closeInventory();
            return;
        }
        if (slot == RecipePreviewGui.prevSlot()) {
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            open(player, holder, holder.index() - 1);
            return;
        }
        if (slot == RecipePreviewGui.nextSlot()) {
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            open(player, holder, holder.index() + 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        // 预览界面禁止任何拖拽（含玩家背包区）
        if (event.getInventory().getHolder() instanceof RecipePreviewGui.PreviewHolder) {
            event.setCancelled(true);
        }
    }

    private void open(Player player, RecipePreviewGui.PreviewHolder holder, int newIndex) {
        player.closeInventory();
        RecipePreviewGui.open(player, holder.materials(), newIndex);
    }
}
