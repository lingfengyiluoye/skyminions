package com.hcs.minions.gui;

import com.hcs.minions.util.EnchantedResource;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 升级材料总览/预览 GUI 交互（{@code /minion materials}）：
 *
 * <ul>
 *   <li>总览页：点击附魔资源卡片 → 打开其合成预览；点关闭 → 关界面；</li>
 *   <li>预览页：点成品槽 → 从背包手动压缩 1 个附魔资源；点返回 → 回总览页；点关闭 → 关界面；</li>
 *   <li>其余区域纯展示：整个视图（含玩家背包区）点击/拖拽一律取消（防刷）。</li>
 * </ul>
 */
public final class UpgradeMaterialsListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        var holder = event.getInventory().getHolder();
        if (holder instanceof UpgradeMaterialsGui.OverviewHolder overview) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!player.getUniqueId().equals(overview.playerId())) {
                player.closeInventory();
                return;
            }
            int slot = event.getRawSlot();
            if (slot >= event.getInventory().getSize()) {
                return;
            }
            if (slot == UpgradeMaterialsGui.overviewCloseSlot()) {
                player.closeInventory();
                return;
            }
            EnchantedResource r = UpgradeMaterialsGui.resourceAt(slot);
            if (r != null) {
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                UpgradeMaterialsGui.openDetail(player, r);
            }
            return;
        }
        if (holder instanceof UpgradeMaterialsGui.DetailHolder detail) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!player.getUniqueId().equals(detail.playerId())) {
                player.closeInventory();
                return;
            }
            int slot = event.getRawSlot();
            if (slot == UpgradeMaterialsGui.detailBackSlot()) {
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                UpgradeMaterialsGui.openOverview(player);
            } else if (slot == UpgradeMaterialsGui.detailCloseSlot()) {
                player.closeInventory();
            } else if (slot == UpgradeMaterialsGui.detailResultSlot()) {
                // 成品槽：从背包手动压缩 1 个附魔资源（材料不足播 deny 音）
                EnchantedResource.ofKey(detail.resourceKey()).ifPresent(r -> {
                    if (UpgradeMaterialsGui.compactFromInventory(player, r)) {
                        UpgradeMaterialsGui.openDetail(player, r); // 重绘：刷新信息卡的背包存量/可压缩数
                    } else {
                        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO,
                                0.8f, 0.8f);
                    }
                });
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        var holder = event.getInventory().getHolder();
        if (holder instanceof UpgradeMaterialsGui.OverviewHolder
                || holder instanceof UpgradeMaterialsGui.DetailHolder) {
            event.setCancelled(true);
        }
    }
}
