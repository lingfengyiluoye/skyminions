package com.hcs.minions.gui;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.event.MinionCollectEvent;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionSkin;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.service.FuelService;
import com.hcs.minions.service.MinionEntityService;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.upgrade.MinionUpgradeType;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.Fx;
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private final ConfigProvider config;
    private final UpgradeService upgrades;
    private final SkyblockHook skyblock;

    public MinionGUIListener(MinionManager manager, MinionItemService items, MinionEntityService entities,
                             ConfigProvider config, UpgradeService upgrades, SkyblockHook skyblock) {
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
        if (slot == Minion.upgradeSlot()) {
            event.setCancelled(true);
            openCraft(player, minion);
            return;
        }
        if (slot == Minion.pickupSlot()) {
            event.setCancelled(true);
            pickup(player, minion);
            return;
        }
        if (slot == Minion.collectSlot()) {
            event.setCancelled(true);
            collectAll(player, minion);
            return;
        }
        if (slot == Minion.closeSlot()) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (slot == Minion.module1Slot() || slot == Minion.module2Slot()
                || slot == Minion.module3Slot() || slot == Minion.module4Slot()) {
            event.setCancelled(true);
            handleUpgradeSlot(event, player, minion, slot);
            return;
        }
        if (slot == Minion.skinSlot()) {
            event.setCancelled(true);
            cycleSkin(player, minion);
            return;
        }
        if (slot == Minion.infoSlot() || slot == Minion.headSlot()) {
            event.setCancelled(true);
            return;
        }
        if (Minion.isStorageSlot(slot)) {
            int index = indexOf(Minion.storageSlots(), slot);
            if (index >= minion.unlockedSlots()) {
                event.setCancelled(true); // 锁定槽
            }
            return; // 解锁槽自由取放
        }
        if (slot == Minion.fuelSlot()) {
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
        ItemStack fuel = minion.storage().getItem(Minion.fuelSlot());
        if (fuel != null && fuel.getType() != Material.AIR && !Minion.isFuelHint(fuel)) {
            FuelService.FuelValue fv = FuelService.valueOf(fuel.getType());
            if (fv != null) {
                if (fv.permanent()) {
                    minion.addPermanentFuel(fv.boost());
                    player.sendMessage(Messages.permanentFuelEquipped((int) ((fv.boost() - 1) * 100)));
                } else {
                    minion.addFuel(fv.durationTicks() * fuel.getAmount(), fv.boost());
                    Fx.ok(player, Messages.fuelAdded((int) ((fv.boost() - 1) * 100)));
            Fx.sound(player, org.bukkit.Sound.ENTITY_GENERIC_DRINK, 1.0f);
                }
            } else {
                giveOrDrop(player, fuel);
            }
            minion.storage().setItem(Minion.fuelSlot(), null);
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
        boolean bucketFuel = cursor.getType() == Material.LAVA_BUCKET;
        int consumed = 0;
        if (fv.permanent()) {
            minion.addPermanentFuel(fv.boost());
            consumed = 1;
            cursor.setAmount(cursor.getAmount() - 1);
            event.setCursor(cursor.getAmount() > 0 ? cursor : null);
            Fx.ok(player, Messages.permanentFuelEquipped((int) ((fv.boost() - 1) * 100)));
        } else if (fv.hasMultiplier()) {
            // 催化剂轴：整组安装时长，仅更强倍率才会被消耗
            int amount = cursor.getAmount();
            if (!minion.addMultiplier(fv.multiplier(), fv.durationTicks() * amount)) {
                Fx.deny(player, Messages.multFuelWeak(minion.prodMultiplier()));
                return;
            }
            consumed = amount;
            event.setCursor(null);
            Fx.ok(player, Messages.multFuelEquipped(fv.multiplier(), fv.durationTicks() * amount / 20L));
        } else {
            int amount = cursor.getAmount();
            consumed = amount;
            event.setCursor(null);
            minion.addFuel(fv.durationTicks() * amount, fv.boost());
            Fx.ok(player, Messages.fuelAdded((int) ((fv.boost() - 1) * 100)));
            Fx.sound(player, org.bukkit.Sound.ENTITY_GENERIC_DRINK, 1.0f);
        }
        if (bucketFuel) {
            // 桶装燃料按实际消耗个数返还空桶（LAVA_BUCKET 为限时燃料，整组消耗，不能用固定 1）
            giveOrDrop(player, new ItemStack(Material.BUCKET, consumed));
        }
        minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
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
        minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
        manager.save(minion);
        Fx.ok(player, Messages.fuelUnequipped());
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

    /** 模块槽交互：手持模块点击装备，空手点击已装备槽卸下（Hypixel 原版，4 槽）。 */
    private void handleUpgradeSlot(InventoryClickEvent event, Player player, Minion minion, int slot) {
        int slotNum;
        if (slot == Minion.module4Slot()) {
            slotNum = 4;
        } else if (slot == Minion.module3Slot()) {
            slotNum = 3;
        } else if (slot == Minion.module2Slot()) {
            slotNum = 2;
        } else {
            slotNum = 1;
        }
        if (minion.unlockedUpgradeSlots() < slotNum) {
            player.sendMessage(Messages.UPGRADE_SLOT_LOCKED);
            return;
        }
        ItemStack cursor = event.getCursor();
        Optional<MinionUpgradeType> held = upgrades.parseType(cursor);

        if (held.isPresent()) {
            MinionUpgradeType type = held.get();
            if (minion.upgradeAt(slotNum) != null) {
                player.sendMessage(Messages.UPGRADE_SLOT_OCCUPIED);
                return;
            }
            // 防止同一模块占用多个槽（重复装同类型无意义，且储物箱叠加会翻倍容量）
            if (minion.hasUpgrade(type)) {
                player.sendMessage(Messages.upgradeDuplicate(type.displayName()));
                return;
            }
            minion.setUpgradeAt(slotNum, type);
            cursor.setAmount(cursor.getAmount() - 1);
            if (cursor.getAmount() <= 0) {
                event.setCursor(null);
            }
            minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
            manager.save(minion); // 装备模块立即登记落库
            player.sendMessage(Messages.upgradeEquipped(type.displayName()));
            return;
        }

        MinionUpgradeType removed = minion.removeUpgradeSlot(slotNum);
        if (removed != null) {
            giveOrDrop(player, upgrades.createItem(removed));
            // 卸下储物箱模块会缩减存储容量：把被重新锁定格里的残留物品交还玩家，防止 refresh 覆盖吞物
            for (ItemStack overflow : minion.evictOverflowAfterRemoving(removed)) {
                giveOrDrop(player, overflow);
            }
            minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
            manager.save(minion); // 卸下模块立即登记落库
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
        minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
        manager.save(minion);
        Fx.ok(player, Messages.skinChanged(minion.skin().displayName()));
    }

    /** 打开 Hypixel 式合成升级界面：3×3 合成格放入上一级本体 + 材料合成下一 Tier。 */
    private void openCraft(Player player, Minion minion) {
        MinionTypeConfig cfg = config.get().type(minion.type());
        if (minion.level() >= cfg.maxLevel()) {
            player.sendMessage(Messages.MAX_LEVEL);
            return;
        }
        UpgradeCraftGui.open(player, minion, items, config);
    }

    private void collectAll(Player player, Minion minion) {
        List<ItemStack> collected = minion.collectAll();
        if (collected.isEmpty()) {
            Fx.deny(player, Messages.STORAGE_EMPTY);
            return;
        }
        // 事件广播：在写入玩家背包前发布，让外部监听器可读到真实取出的产物列表
        Bukkit.getPluginManager().callEvent(new MinionCollectEvent(minion, player, collected));
        giveOrDrop(player, collected.toArray(new ItemStack[0]));
        manager.save(minion); // 清仓状态立即登记落库
        minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
        com.hcs.minions.util.Fx.pickup(player);
        player.sendMessage(Messages.COLLECTED_ALL);
    }

    private void pickup(Player player, Minion minion) {
        ItemStack spawner;
        try {
            spawner = items.createItemFromMinion(minion);
        } catch (Exception e) {
            // 生成物异常时不移除、不关界面（玩家可重试）；留日志便于定位
            Logs.error("拾取失败（生成物构造异常）: id=" + minion.id() + ", type=" + minion.type(), e);
            player.sendMessage(Component.text("拾取失败：物品生成异常，详情见控制台日志", NamedTextColor.RED));
            return;
        }
        player.closeInventory();
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
