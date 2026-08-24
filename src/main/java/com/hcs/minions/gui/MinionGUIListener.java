package com.hcs.minions.gui;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.event.MinionLevelUpEvent;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionSkin;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.service.FuelService;
import com.hcs.minions.service.MinionEntityService;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.upgrade.MinionUpgradeType;
import com.hcs.minions.upgrade.UpgradeRules;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.ItemRef;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 仆从 GUI 监听（Hypixel 式 54 格单界面）。
 */
public final class MinionGUIListener implements Listener {

    private final MinionManager manager;
    private final MinionItemService items;
    private final MinionEntityService entities;
    private final PluginConfig config;
    private final UpgradeService upgrades;
    private final SkyblockHook skyblock;

    public MinionGUIListener(MinionManager manager, MinionItemService items, MinionEntityService entities,
                             PluginConfig config, UpgradeService upgrades, SkyblockHook skyblock) {
        this.manager = manager;
        this.items = items;
        this.entities = entities;
        this.config = config;
        this.upgrades = upgrades;
        this.skyblock = skyblock;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Minion.StorageHolder holder)) {
            return;
        }
        Minion minion = manager.minion(holder.minionId());
        if (minion == null) {
            event.getWhoClicked().closeInventory();
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!skyblock.canUse(minion, player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        int slot = event.getRawSlot();
        if (slot >= event.getInventory().getSize()) {
            return; // 玩家背包区自由操作（拿起物品/整理背包）——否则无法手持燃料与物品
        }
        if (slot == Minion.UPGRADE_SLOT) {
            event.setCancelled(true);
            upgrade(player, minion);
            return;
        }
        if (slot == Minion.AUTOSELL_SLOT) {
            event.setCancelled(true);
            minion.setAutoSell(!minion.autoSell());
            minion.refresh(config.type(minion.type()));
            player.sendMessage(Messages.autoSellToggled(minion.autoSell()));
            return;
        }
        if (slot == Minion.PICKUP_SLOT) {
            event.setCancelled(true);
            pickup(player, minion);
            return;
        }
        if (slot == Minion.COLLECT_SLOT) {
            event.setCancelled(true);
            collectAll(player, minion);
            return;
        }
        if (slot == Minion.CLOSE_SLOT) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (slot == Minion.UPGRADE1_SLOT || slot == Minion.UPGRADE2_SLOT) {
            event.setCancelled(true);
            handleUpgradeSlot(event, player, minion, slot);
            return;
        }
        if (slot == Minion.SKIN_SLOT) {
            event.setCancelled(true);
            cycleSkin(player, minion);
            return;
        }
        if (slot == Minion.LAYOUT_SLOT) {
            event.setCancelled(true);
            showLayout(player, minion);
            return;
        }
        if (slot == Minion.INFO_SLOT || slot == Minion.HEAD_SLOT) {
            event.setCancelled(true);
            return;
        }
        if (Minion.isStorageSlot(slot)) {
            int index = indexOf(Minion.STORAGE_SLOTS, slot);
            if (index >= minion.unlockedSlots()) {
                event.setCancelled(true); // 锁定槽
            }
            return; // 解锁槽自由取放
        }
        if (slot == Minion.FUEL_SLOT) {
            event.setCancelled(true); // 燃料槽为按钮（提示卡不可拿走）
            handleFuelClick(event, player, minion);
            return;
        }
        event.setCancelled(true); // 装饰槽
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Minion.StorageHolder holder)) {
            return;
        }
        Minion minion = manager.minion(holder.minionId());
        if (minion == null || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        // 燃料槽已是按钮（点击即结算），此处仅兜底：槽内残留燃料直接结算，非燃料物品还给玩家
        ItemStack fuel = minion.storage().getItem(Minion.FUEL_SLOT);
        if (fuel != null && fuel.getType() != Material.AIR && !Minion.isFuelHint(fuel)) {
            FuelService.FuelValue fv = FuelService.valueOf(fuel.getType());
            if (fv != null) {
                if (fv.permanent()) {
                    minion.addPermanentFuel(fv.boost());
                    player.sendMessage(Messages.permanentFuelEquipped((int) ((fv.boost() - 1) * 100)));
                } else {
                    minion.addFuel(fv.durationTicks() * fuel.getAmount(), fv.boost());
                    player.sendMessage(Messages.fuelAdded((int) ((fv.boost() - 1) * 100)));
                }
            } else {
                giveOrDrop(player, fuel);
            }
            minion.storage().setItem(Minion.FUEL_SLOT, null);
        }
        minion.markDirty();
        manager.save(minion);
    }

    /** 拖拽保护：GUI 的按钮槽（燃料/信息/头颅等非存储槽）不允许拖入物品；无使用权全部禁止。 */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Minion.StorageHolder holder)) {
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
        for (int raw : event.getRawSlots()) {
            if (raw < event.getInventory().getSize() && !Minion.isStorageSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** 燃料槽交互（Hypixel 原版 + 选择界面）：手持有效燃料点击立即生效；
     *  空手点击打开燃料选择 GUI（从背包装）；潜行空手点击卸下限时燃料。 */
    private void handleFuelClick(InventoryClickEvent event, Player player, Minion minion) {
        ItemStack cursor = event.getCursor();
        FuelService.FuelValue fv = cursor == null ? null : FuelService.valueOf(cursor.getType());
        if (fv == null) {
            if (event.isShiftClick()) {
                unequipTimedFuel(player, minion);
            } else {
                FuelGui.open(player, minion);
            }
            return;
        }
        if (fv.permanent()) {
            minion.addPermanentFuel(fv.boost());
            cursor.setAmount(cursor.getAmount() - 1);
            event.setCursor(cursor.getAmount() > 0 ? cursor : null);
            player.sendMessage(Messages.permanentFuelEquipped((int) ((fv.boost() - 1) * 100)));
        } else {
            int amount = cursor.getAmount();
            event.setCursor(null);
            minion.addFuel(fv.durationTicks() * amount, fv.boost());
            player.sendMessage(Messages.fuelAdded((int) ((fv.boost() - 1) * 100)));
        }
        minion.refresh(config.type(minion.type()));
        manager.save(minion);
    }

    /** 潜行点击燃料槽：卸下限时燃料（清空剩余时间）；永久燃料不可卸下。 */
    private void unequipTimedFuel(Player player, Minion minion) {
        if (minion.fuelTicks() <= 0) {
            sendFuelHelp(player, minion);
            return;
        }
        minion.setFuelTicks(0);
        minion.setFuelBoost(1.0);
        minion.refresh(config.type(minion.type()));
        manager.save(minion);
        player.sendMessage(Messages.fuelUnequipped());
    }

    /** 燃料指引：操作方式 + 当前燃料状态 + 全部可用燃料列表。 */
    private void sendFuelHelp(Player player, Minion minion) {
        player.sendMessage(Messages.FUEL_HELP_HEADER);
        if (minion.permanentBoost() > 1.0) {
            player.sendMessage(Messages.fuelStatusPermanent((int) ((minion.permanentBoost() - 1) * 100)));
        } else if (minion.fuelTicks() > 0) {
            player.sendMessage(Messages.fuelStatusTimed(minion.fuelTicks() / 20));
        } else {
            player.sendMessage(Messages.FUEL_STATUS_NONE);
        }
        player.sendMessage(Messages.FUEL_HELP_TIP);
        for (Map.Entry<Material, FuelService.FuelValue> e : FuelService.all().entrySet()) {
            FuelService.FuelValue fv = e.getValue();
            String duration = fv.permanent() ? "永久" : fmtDuration(fv.durationTicks());
            player.sendMessage(Messages.fuelHelpLine(MaterialNames.of(e.getKey()),
                    String.valueOf((int) ((fv.boost() - 1) * 100)), duration));
        }
    }

    private static String fmtDuration(long ticks) {
        long seconds = ticks / 20;
        if (seconds >= 3600 && seconds % 3600 == 0) {
            return (seconds / 3600) + " 小时";
        }
        if (seconds >= 60) {
            return (seconds / 60) + " 分钟";
        }
        return seconds + " 秒";
    }

    /** 模块槽交互：手持模块点击装备，空手点击已装备槽卸下（Hypixel 原版）。 */
    private void handleUpgradeSlot(InventoryClickEvent event, Player player, Minion minion, int slot) {
        int slotNum = (slot == Minion.UPGRADE2_SLOT) ? 2 : 1;
        if (minion.unlockedUpgradeSlots() < slotNum) {
            player.sendMessage(Messages.UPGRADE_SLOT_LOCKED);
            return;
        }
        ItemStack cursor = event.getCursor();
        Optional<MinionUpgradeType> held = upgrades.parseType(cursor);

        if (held.isPresent()) {
            MinionUpgradeType type = held.get();
            boolean occupied = slotNum == 1 ? (minion.upgrade1() != null) : (minion.upgrade2() != null);
            if (occupied) {
                player.sendMessage(Messages.UPGRADE_SLOT_OCCUPIED);
                return;
            }
            if (slotNum == 1) {
                minion.setUpgrade1(type);
            } else {
                minion.setUpgrade2(type);
            }
            cursor.setAmount(cursor.getAmount() - 1);
            if (cursor.getAmount() <= 0) {
                event.setCursor(null);
            }
            minion.refresh(config.type(minion.type()));
            player.sendMessage(Messages.upgradeEquipped(type.displayName()));
            return;
        }

        MinionUpgradeType removed = minion.removeUpgradeSlot(slotNum);
        if (removed != null) {
            giveOrDrop(player, upgrades.createItem(removed));
            minion.refresh(config.type(minion.type()));
            player.sendMessage(Messages.upgradeRemoved(removed.displayName()));
        } else {
            player.sendMessage(Messages.UPGRADE_SLOT_EMPTY);
        }
    }

    /** 循环切换皮肤。 */
    private void cycleSkin(Player player, Minion minion) {
        MinionSkin[] skins = MinionSkin.values();
        int next = (minion.skin().ordinal() + 1) % skins.length;
        minion.setSkin(skins[next]);
        // 刷新盔甲架外观（头盔贴图随皮肤变化），而非只改内存字段
        entities.refreshAppearance(minion);
        minion.refresh(config.type(minion.type()));
        manager.save(minion);
        player.sendMessage(Messages.skinChanged(minion.skin().displayName()));
    }

    /** 理想布局：圆石仆从为可执行开关（自动摆水/岩浆，见 GeneratorStrategy）；其余类型展示布局指引。 */
    private void showLayout(Player player, Minion minion) {
        MinionTypeConfig cfg = config.type(minion.type());
        if (minion.type() == MinionType.COBBLE) {
            boolean on = !minion.idealLayout();
            if (!on) {
                minion.cleanupLayoutBlocks(); // 关闭时还原摆放的水/岩浆
            }
            minion.setIdealLayout(on);
            minion.refresh(cfg);
            manager.save(minion);
            player.sendMessage(on ? Messages.layoutEnabled() : Messages.layoutDisabled());
            return;
        }
        int r = cfg.radiusFor(minion.level());
        int side = 2 * r + 1;
        player.sendMessage(Messages.LAYOUT_HEADER);
        player.sendMessage(Messages.layoutRange(side));
        player.sendMessage(Messages.LAYOUT_TIP_CENTER);
        player.sendMessage(Messages.LAYOUT_TIP_LIGHT);
        player.sendMessage(Messages.LAYOUT_TIP_SHARED);
    }

    private void upgrade(Player player, Minion minion) {
        MinionTypeConfig cfg = config.type(minion.type());
        if (minion.level() >= cfg.maxLevel()) {
            player.sendMessage(Messages.MAX_LEVEL);
            return;
        }
        Map<ItemRef, Long> recipe = cfg.recipeFor(minion.level());
        // 先只读校验材料 + 本体，全部齐备才一起扣（避免扣了材料才发现缺本体）
        Map<ItemRef, Long> missing = minion.missingRecipe(recipe);
        if (!missing.isEmpty()) {
            player.sendMessage(Messages.upgradeFailed(formatMissing(missing)));
            return;
        }
        boolean needBody = UpgradeRules.needsPreviousBody(
                minion.level(), cfg.maxLevel(), config.upgradeRequirePreviousBody());
        if (needBody && !hasPreviousBody(player, minion)) {
            player.sendMessage(Messages.upgradeMissingBody(cfg.displayName(), minion.level()));
            return;
        }
        minion.consumeRecipe(recipe);
        if (needBody) {
            consumePreviousBody(player, minion);
        }
        minion.upgrade(cfg);
        Bukkit.getPluginManager().callEvent(new MinionLevelUpEvent(minion, minion.level()));
        minion.refresh(cfg);
        player.sendMessage(Messages.upgradeSuccess(minion.level()));
    }

    /** 匹配「当前等级的同类型仆从生成物」（合成升级的上一级本体）。 */
    private boolean isPreviousBody(ItemStack item, Minion minion) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return false;
        }
        return items.parseType(item).map(t -> t == minion.type()).orElse(false)
                && items.parseLevel(item) == minion.level();
    }

    /** 检查仓库或背包中是否存在上一级本体（不扣除）。 */
    private boolean hasPreviousBody(Player player, Minion minion) {
        if (minion.findSlot(item -> isPreviousBody(item, minion)) >= 0) {
            return true;
        }
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isPreviousBody(item, minion)) {
                return true;
            }
        }
        return false;
    }

    /** 扣除 1 个上一级本体：优先仆从仓库，其次玩家背包。 */
    private void consumePreviousBody(Player player, Minion minion) {
        int slot = minion.findSlot(item -> isPreviousBody(item, minion));
        if (slot >= 0) {
            minion.takeOne(slot);
            return;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (isPreviousBody(contents[i], minion)) {
                contents[i].setAmount(contents[i].getAmount() - 1);
                if (contents[i].getAmount() <= 0) {
                    contents[i] = null;
                }
                player.getInventory().setStorageContents(contents);
                return;
            }
        }
    }

    /** 缺失材料清单格式化为 "红石 ×8、煤炭 ×16" 供提示。 */
    private static String formatMissing(Map<ItemRef, Long> missing) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ItemRef, Long> e : missing.entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(e.getKey().displayName()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }

    private void collectAll(Player player, Minion minion) {
        List<ItemStack> collected = minion.collectAll();
        if (collected.isEmpty()) {
            player.sendMessage(Messages.STORAGE_EMPTY);
            return;
        }
        giveOrDrop(player, collected.toArray(new ItemStack[0]));
        minion.refresh(config.type(minion.type()));
        player.sendMessage(Messages.COLLECTED_ALL);
    }

    private void pickup(Player player, Minion minion) {
        ItemStack spawner = items.createItemFromMinion(minion);
        player.closeInventory();
        minion.cleanupLayoutBlocks(); // 拾取时还原理想布局摆放的水/岩浆
        manager.remove(minion, player);
        giveOrDrop(player, spawner);
    }

    private void giveOrDrop(Player player, ItemStack... itemStacks) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(itemStacks);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private int indexOf(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
