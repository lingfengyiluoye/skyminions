package com.hcs.minions.gui;

import com.hcs.minions.util.Sounds;
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
 *   <li>总览页：点击附魔资源卡片 → 打开其合成预览；翻页/关闭；</li>
 *   <li>预览页：点成品槽 → 从背包手动压缩 1 个附魔资源；点返回 → 回总览来源页；点关闭 → 关界面；</li>
 *   <li>其余区域纯展示：整个视图（含玩家背包区）点击/拖拽一律取消（防刷）。</li>
 * </ul>
 *
 * <p>界面切换一律「先取消事件 → 先关旧界面再开新界面」，避免同 tick 内 openInventory 竞态。</p>
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
            if (slot == UpgradeMaterialsGui.prevSlot()) {
                player.closeInventory();
                UpgradeMaterialsGui.openOverview(player, overview.page() - 1);
                return;
            }
            if (slot == UpgradeMaterialsGui.nextSlot()) {
                player.closeInventory();
                UpgradeMaterialsGui.openOverview(player, overview.page() + 1);
                return;
            }
            EnchantedResource r = UpgradeMaterialsGui.resourceAt(slot, overview.page());
            if (r != null) {
                Sounds.click(player);
                player.closeInventory();
                UpgradeMaterialsGui.openDetail(player, r, overview.page());
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
                Sounds.click(player);
                player.closeInventory();
                UpgradeMaterialsGui.openOverview(player, detail.fromPage());
            } else if (slot == UpgradeMaterialsGui.detailCloseSlot()) {
                player.closeInventory();
            } else if (slot == UpgradeMaterialsGui.detailResultSlot()) {
                // 成品槽：从背包手动压缩 1 个附魔资源（材料不足播 deny 音）
                EnchantedResource.ofKey(detail.resourceKey()).ifPresent(r -> {
                    if (UpgradeMaterialsGui.compactFromInventory(player, r)) {
                        // 重绘：刷新信息卡的背包存量/可压缩数（先关后开）
                        player.closeInventory();
                        UpgradeMaterialsGui.openDetail(player, r, detail.fromPage());
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
