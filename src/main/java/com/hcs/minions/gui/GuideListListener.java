package com.hcs.minions.gui;

import com.hcs.minions.util.ItemRef;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 材料指南清单页交互：
 *
 * <ul>
 *   <li>整个视图（含玩家背包区）点击/拖拽一律取消（防刷）；</li>
 *   <li>点击<b>可合成</b>材料 → 打开该材料的图形化合成预览（◀ ▶ 仍可在全部材料间循环）；</li>
 *   <li>点击<b>不可合成</b>材料 → 无反应（点不开）；</li>
 *   <li>关闭按钮 → 关闭界面。</li>
 * </ul>
 */
public final class GuideListListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuideListGui.GuideListHolder holder)) {
            return;
        }
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
        if (slot >= size) {
            return; // 玩家背包区：已取消，无行为
        }
        if (slot == GuideListGui.closeSlot()) {
            player.closeInventory();
            return;
        }
        int idx = GuideListGui.indexOfSlot(slot);
        if (idx < 0 || idx >= holder.entries().size()) {
            return;
        }
        ItemRef ref = holder.entries().get(idx).getKey();
        Material mat = ref.guideMaterial();
        // 不可合成的材料点不开：没有对应预览形状
        if (com.hcs.minions.util.MaterialGuide.gridOf(mat).isEmpty()) {
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.4f, 0.8f);
            return;
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        Material target = holder.allMaterials().get(idx);
        int previewIndex = Math.max(0, holder.allMaterials().indexOf(target));
        player.closeInventory();
        RecipePreviewGui.open(player, holder.allMaterials(), previewIndex);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuideListGui.GuideListHolder) {
            event.setCancelled(true);
        }
    }
}
