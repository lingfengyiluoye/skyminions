package com.hcs.minions.listener;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.model.BlockLocation;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.service.CollectionService;
import com.hcs.minions.service.FuelService;
import com.hcs.minions.service.MinionEntityService;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.PermissionService;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Messages;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 仆从放置与交互监听。放置生成盔甲架小人；右键打开仓库、潜行右键拾取、手持燃料补燃料。
 * 放置受 LuckPerms 权限（hcs.minions.type.* / hcs.minions.limit.*）限制。
 */
public final class MinionInteractionListener implements Listener {

    private final MinionManager manager;
    private final MinionItemService items;
    private final MinionEntityService entities;
    private final PermissionService permissions;
    private final CollectionService collection;
    private final SkyblockHook skyblock;
    private final PluginConfig config;

    public MinionInteractionListener(MinionManager manager, MinionItemService items, MinionEntityService entities,
                                     PermissionService permissions, CollectionService collection,
                                     SkyblockHook skyblock, PluginConfig config) {
        this.manager = manager;
        this.items = items;
        this.entities = entities;
        this.permissions = permissions;
        this.collection = collection;
        this.skyblock = skyblock;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        Optional<MinionType> type = items.parseType(hand);
        if (type.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (!permissions.canUseType(player, type.get())) {
            MinionTypeConfig lockedCfg = config.type(type.get());
            if (lockedCfg != null && !permissions.isUnlocked(player, type.get())) {
                // 收集量未达标：提示解锁进度而非笼统的无权限
                player.sendMessage(Messages.unlockRequired(MaterialNames.of(lockedCfg.product()),
                        lockedCfg.unlockAmount(), collection.get(player.getUniqueId(), lockedCfg.product())));
            } else {
                player.sendMessage(Messages.NO_PERMISSION);
            }
            return;
        }
        if (!skyblock.canPlaceAt(block.getLocation())) {
            player.sendMessage(Messages.MUST_PLACE_ON_ISLAND);
            return;
        }

        MinionTypeConfig cfg = config.type(type.get());
        long fuel = items.parseFuel(hand);
        Minion minion = new Minion(
                UUID.randomUUID(), player.getUniqueId(), type.get(),
                items.parseLevel(hand),
                BlockLocation.of(block),
                fuel > 0 ? fuel : cfg.baseFuelTicks(),
                System.currentTimeMillis(), null
        );
        minion.setStorageItems(items.parseStorage(hand));
        minion.setUpgrade1(items.parseUpgrade1(hand));
        minion.setUpgrade2(items.parseUpgrade2(hand));
        minion.setSkin(items.parseSkin(hand));
        // 放置时固定面向放置者（Hypixel 风格），避免每次朝向随机
        minion.setFacing(yawTowards(block.getLocation().add(0.5, 0.0, 0.5), player.getLocation()));
        skyblock.cacheIslandFor(minion, block.getLocation());

        if (!manager.place(minion, player)) {
            player.sendMessage(Messages.LIMIT_REACHED);
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
    }

    /** 从 from 望向 to 的 yaw（Minecraft 约定：yaw=0 朝 +z），取水平方向忽略高度差。 */
    private static float yawTowards(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // 注意：盔甲架右键必须用 PlayerInteractAtEntityEvent（SPIGOT-122：
    // PlayerInteractEntityEvent 不会为 ArmorStand 触发）。
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof ArmorStand stand)) {
            return;
        }
        UUID id = entities.minionIdOf(stand);
        if (id == null) {
            return;
        }
        Minion minion = manager.minion(id);
        if (minion == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!skyblock.canUse(minion, player.getUniqueId())) {
            player.sendMessage(Messages.NOT_YOUR_MINION);
            return;
        }

        if (player.isSneaking()) {
            pickup(player, minion);
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        FuelService.FuelValue fuelValue = FuelService.valueOf(hand.getType());
        if (fuelValue != null) {
            if (fuelValue.permanent()) {
                minion.addPermanentFuel(fuelValue.boost());
                hand.setAmount(hand.getAmount() - 1);
                player.sendMessage(Messages.permanentFuelEquipped((int) ((fuelValue.boost() - 1) * 100)));
            } else {
                minion.addFuel(fuelValue.durationTicks() * hand.getAmount(), fuelValue.boost());
                hand.setAmount(0);
                player.sendMessage(Messages.fuelAdded((int) ((fuelValue.boost() - 1) * 100)));
            }
            return;
        }

        manager.openGui(player, minion);
    }

    private void pickup(Player player, Minion minion) {
        minion.cleanupLayoutBlocks(); // 拾取时还原理想布局摆放的水/岩浆
        ItemStack spawner = items.createItemFromMinion(minion);
        manager.remove(minion, player);
        giveOrDrop(player, spawner);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
