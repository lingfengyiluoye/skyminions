package com.hcs.minions.gui;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.event.MinionLevelUpEvent;
import com.hcs.minions.model.Minion;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 升级合成 GUI 交互：合成格自由取放（材料来自玩家背包），材料齐后点击结果槽
 * 消耗格内物品并原地升级仆从；关闭/返回时格内残留物品归还玩家。
 */
public final class UpgradeCraftGuiListener implements Listener {

    private final JavaPlugin plugin;
    private final MinionManager manager;
    private final MinionItemService items;
    private final ConfigProvider config;
    private final SkyblockHook skyblock;

    public UpgradeCraftGuiListener(JavaPlugin plugin, MinionManager manager, MinionItemService items,
                                   ConfigProvider config, SkyblockHook skyblock) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
        this.config = config;
        this.skyblock = skyblock;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof UpgradeCraftGui.CraftHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        Minion minion = manager.minion(holder.minionId());
        if (minion == null) {
            player.closeInventory();
            return;
        }
        if (!skyblock.canUse(minion, player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Inventory inv = event.getInventory();
        int slot = event.getRawSlot();
        if (slot >= inv.getSize()) {
            // 玩家背包区自由操作；潜行快速移入合成格后延迟刷新结果槽
            if (event.isShiftClick()) {
                scheduleRefresh(inv, minion);
            }
            return;
        }
        if (UpgradeCraftGui.isGridSlot(slot)) {
            scheduleRefresh(inv, minion); // 放置/取走在事件后生效，下一 tick 重算结果
            return;
        }
        event.setCancelled(true);
        if (slot == UpgradeCraftGui.resultSlot()) {
            handleCraft(player, inv, minion);
            return;
        }
        if (slot == UpgradeCraftGui.infoSlot() && event.isShiftClick()) {
            UpgradeCraftGui.fillFromInventory(inv, player, minion, items,
                    config.get().type(minion.type()), config);
            UpgradeCraftGui.refresh(inv, minion, items, config);
            return;
        }
        if (slot == UpgradeCraftGui.guideSlot()) {
            com.hcs.minions.util.Fx.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.1f);
            sendGuide(player, minion);
            return;
        }
        if (slot == UpgradeCraftGui.backSlot()) {
            manager.openGui(player, minion); // 关闭时 onClose 归还格内物品
        }
        // 箭头与装饰槽：仅拦截
    }

    /** 材料指南：打开两级导航的清单页（材料+数量），点击可合成项进入图形化预览。 */
    private void sendGuide(Player player, Minion minion) {
        try {
            com.hcs.minions.config.MinionTypeConfig cfg = config.get().type(minion.type());
            var recipe = cfg.recipeFor(minion.level());
            // 已有数量（按 ItemRef 统计，附魔资源与同材质原版材料分开计）
            java.util.Map<com.hcs.minions.util.ItemRef, Long> owned = new java.util.LinkedHashMap<>();
            for (var ref : recipe.keySet()) {
                owned.put(ref, minion.countInStorage(ref));
            }
            GuideListGui.open(player, cfg.displayName(),
                    com.hcs.minions.util.Roman.of(minion.level()),
                    com.hcs.minions.util.Roman.of(minion.level() + 1),
                    recipe, owned, player.getUniqueId(), minion.level());
        } catch (Throwable t) {
            // 兜底：指南打开失败只影响本按钮，绝不带崩服务器
            com.hcs.minions.util.Logs.error("材料指南清单打开失败: minion=" + minion.id(), t);
            player.sendMessage(Messages.MATERIALS_GUIDE_FAILED);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof UpgradeCraftGui.CraftHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        Minion minion = manager.minion(holder.minionId());
        if (minion == null || !skyblock.canUse(minion, player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Inventory inv = event.getInventory();
        for (int raw : event.getRawSlots()) {
            if (raw < inv.getSize() && !UpgradeCraftGui.isGridSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleRefresh(inv, minion);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof UpgradeCraftGui.CraftHolder)) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            UpgradeCraftGui.returnGridItems(event.getInventory(), player);
        }
    }

    /** 点击结果槽：材料精确齐备才合成（清空合成格 → 原地升级 → 返回仆从界面）。 */
    private void handleCraft(Player player, Inventory inv, Minion minion) {
        MinionTypeConfig cfg = config.get().type(minion.type());
        if (minion.level() >= cfg.maxLevel()) {
            player.sendMessage(Messages.MAX_LEVEL);
            player.closeInventory();
            return;
        }
        UpgradeCraftGui.CraftCheck check = UpgradeCraftGui.validate(inv, minion, items, cfg, config);
        if (!check.complete()) {
            com.hcs.minions.util.Fx.deny(player,
                    "<red>材料未集齐，还差 " + formatMissing(check) + "</red>");
            player.sendMessage(Messages.upgradeFailed(formatMissing(check)));
            return;
        }
        UpgradeCraftGui.clearGrid(inv);
        minion.upgrade(cfg);
        Bukkit.getPluginManager().callEvent(new MinionLevelUpEvent(minion, minion.level()));
        minion.refresh(cfg, config.get().upgradeRequirePreviousBody());
        manager.save(minion);
        player.sendMessage(Messages.upgradeSuccess(minion.level()));
        com.hcs.minions.util.Fx.title(player, "<gold>✔ 升级成功</gold>", "<gray>当前 等级 " + minion.level() + "</gray>");
        // 点击事件中直接开新界面可能不被客户端接受：先关闭，下一 tick 重开仆从界面
        // （onClose 时合成格已清空，不会误归还）。使用 RegionScheduler 保持 Folia 兼容
        player.closeInventory();
        Bukkit.getRegionScheduler().run(plugin, player.getLocation(), ignored -> {
            if (player.isOnline() && manager.minion(minion.id()) != null) {
                manager.openGui(player, minion);
            }
        });
    }

    /** 缺失清单格式化：材料不足与缺本体合并提示。 */
    private static String formatMissing(UpgradeCraftGui.CraftCheck check) {
        StringBuilder sb = new StringBuilder();
        for (var e : check.missing().entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(e.getKey().displayName()).append(" ×").append(e.getValue());
        }
        if (check.bodyMissing()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append("仆从本体 ×1");
        }
        if (sb.length() == 0) {
            sb.append("格内存在多余物品");
        }
        return sb.toString();
    }

    /** 格子内容变化在点击事件之后生效，延迟 1 tick 刷新信息卡与结果槽（RegionScheduler，Folia 兼容）。 */
    private void scheduleRefresh(Inventory inv, Minion minion) {
        // 以查看者所在 region 调度：Inventory 归属其打开的界面
        org.bukkit.entity.Player viewer = null;
        for (org.bukkit.entity.HumanEntity h : inv.getViewers()) {
            if (h instanceof org.bukkit.entity.Player p) {
                viewer = p;
                break;
            }
        }
        if (viewer == null) {
            return; // 界面已无人观看，无需刷新
        }
        Bukkit.getRegionScheduler().run(plugin, viewer.getLocation(), ignored -> {
            if (inv.getHolder() instanceof UpgradeCraftGui.CraftHolder) {
                UpgradeCraftGui.refresh(inv, minion, items, config);
            }
        });
    }
}
