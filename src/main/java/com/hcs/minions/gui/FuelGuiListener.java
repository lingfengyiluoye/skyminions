package com.hcs.minions.gui;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.model.Minion;
import com.hcs.minions.service.FuelService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.util.GuiLayout;
import com.hcs.minions.util.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * 燃料选择 GUI 交互：点击选项即从背包装燃料；状态卡潜行点击卸下限时燃料。
 */
public final class FuelGuiListener implements Listener {

    private final MinionManager manager;
    private final ConfigProvider config;

    public FuelGuiListener(MinionManager manager, ConfigProvider config) {
        this.manager = manager;
        this.config = config;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FuelGui.FuelHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Minion minion = manager.minion(holder.minionId());
        if (minion == null) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot >= event.getInventory().getSize()) {
            return;
        }
        if (slot == FuelGui.closeSlot()) {
            manager.openGui(player, minion); // 返回仆从仓库界面
            return;
        }
        if (slot == FuelGui.statusSlot()) {
            unequip(player, minion, event.isShiftClick());
            return;
        }
        if (isOptionSlot(slot)) {
            ItemStack option = event.getInventory().getItem(slot);
            if (option == null || option.getType() == GuiLayout.material("fuel-gui.empty.material")) {
                return;
            }
            // 修复「安装全部」：展示项 amount 恒为 1 不能作为数量来源，
            // 以点击时背包实际库存为准（潜行=仅 1 个）
            int want = event.isShiftClick() ? 1 : countInInventory(player, option.getType());
            install(player, minion, option.getType(), want);
        }
    }

    /** 拖拽保护：顶部选项区为展示按钮，禁止拖入；仅玩家背包区内部的拖拽放行。 */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof FuelGui.FuelHolder)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < event.getInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static boolean isOptionSlot(int slot) {
        for (int s : FuelGui.optionSlots()) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    /** 从背包装燃料：潜行装 1 个，否则装该选项显示的全部库存（与手持点击燃料槽行为一致）。 */
    private void install(Player player, Minion minion, Material material, int wantAmount) {
        FuelService.FuelValue fv = FuelService.valueOf(material);
        if (fv == null) {
            return;
        }
        int owned = countInInventory(player, material);
        int amount = Math.min(owned, Math.max(1, wantAmount));
        if (fv.permanent()) {
            amount = Math.min(amount, 1); // 永久燃料仅消耗 1 个
        }
        if (amount <= 0) {
            return;
        }
        if (fv.hasMultiplier()) {
            // 催化剂：仅更强倍率才安装并消耗，弱者不扣库存
            if (!minion.addMultiplier(fv.multiplier(), fv.durationTicks() * amount)) {
                player.sendMessage(Messages.multFuelWeak(minion.prodMultiplier()));
                return;
            }
            removeFromInventory(player, material, amount);
            player.sendMessage(Messages.multFuelEquipped(fv.multiplier(), fv.durationTicks() * amount / 20L));
            minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
            manager.save(minion);
            FuelGui.open(player, minion);
            return;
        }
        if (!removeFromInventory(player, material, amount)) {
            return;
        }
        if (material == Material.LAVA_BUCKET) {
            // 桶装燃料按消耗个数返还空桶（对齐原版习惯）。addItem 会自动按最大堆叠拆分，
            // 因此按 amount 全额返还即可，无需对最大堆叠取 min（否则一次进料 >16 桶会吞桶）。
            giveOrDrop(player, new org.bukkit.inventory.ItemStack(Material.BUCKET, amount));
        }
        if (fv.permanent()) {
            minion.addPermanentFuel(fv.boost());
            player.sendMessage(Messages.permanentFuelEquipped((int) ((fv.boost() - 1) * 100)));
        } else {
            minion.addFuel(fv.durationTicks() * amount, fv.boost());
            player.sendMessage(Messages.fuelAdded((int) ((fv.boost() - 1) * 100)));
        }
        minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
        manager.save(minion);
        FuelGui.open(player, minion); // 重建界面：刷新库存数量与状态卡
    }

    /** 卸下燃料：限时燃料清空剩余时间；永久燃料不可卸下（损耗型装备）。 */
    private void unequip(Player player, Minion minion, boolean shift) {
        if (!shift) {
            player.sendMessage(Messages.FUEL_STATUS_NONE);
            return;
        }
        if (minion.fuelTicks() <= 0) {
            player.sendMessage(Messages.fuelUnequipEmpty());
            return;
        }
        minion.setFuelTicks(0);
        minion.setFuelBoost(1.0);
        minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
        manager.save(minion);
        player.sendMessage(Messages.fuelUnequipped());
        FuelGui.open(player, minion);
    }

    private static int countInInventory(Player player, Material material) {
        int n = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                n += item.getAmount();
            }
        }
        return n;
    }

    /** 从背包扣除指定数量（跨堆叠），成功返回 true。 */
    private static boolean removeFromInventory(Player player, Material material, int amount) {
        if (countInInventory(player, material) < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (item.getAmount() <= 0) {
                contents[i] = null;
            }
        }
        player.getInventory().setStorageContents(contents);
        return remaining == 0;
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
