package com.hcs.minions.gui;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.model.Minion;
import com.hcs.minions.config.FuelEntry;
import com.hcs.minions.service.FuelService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.util.Sounds;
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
            player.closeInventory(); // 先关燃料界面再返回仆从仓库界面
            manager.openGui(player, minion);
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
            // 燃料条目来自 holder 的槽位映射：附魔资源催化剂与同材质散装物品
            // 只能靠条目身份区分（图标都是基底材质，按物品反查会丢身份）
            FuelEntry fv = holder.optionAt(slot);
            if (fv == null) {
                return;
            }
            // 以「背包实际库存」为准（潜行=仅 1 个）；展示项 amount 恒为 1 不能当数量来源
            int want = event.isShiftClick() ? 1 : FuelService.countOwned(player, fv);
            install(player, minion, fv, want);
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
    private void install(Player player, Minion minion, FuelEntry fv, int wantAmount) {
        int owned = FuelService.countOwned(player, fv);
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
            FuelService.removeOwned(player, fv, amount);
            player.sendMessage(Messages.multFuelEquipped(fv.multiplier(), fv.durationTicks() * amount / 20L));
            minion.refresh(config.get().type(minion.type()), config.get().upgradeRequirePreviousBody());
            manager.save(minion);
            FuelGui.open(player, minion);
            return;
        }
        if (!FuelService.removeOwned(player, fv, amount)) {
            return;
        }
        if (fv.hasEmptyContainer()) {
            // 桶装燃料按消耗个数返还空桶（addItem 自动按最大堆叠拆分）
            giveOrDrop(player, new org.bukkit.inventory.ItemStack(fv.returnsEmpty(), amount));
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
        Sounds.click(player); // 安装完成：轻确认音（燃料本身的音效已在 MinionGUIListener 手持路径）
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
        Sounds.click(player);
        FuelGui.open(player, minion);
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
