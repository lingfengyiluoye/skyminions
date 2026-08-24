package com.hcs.minions.listener;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
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
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private final ConfigProvider config;

    public MinionInteractionListener(MinionManager manager, MinionItemService items, MinionEntityService entities,
                                     PermissionService permissions, CollectionService collection,
                                     SkyblockHook skyblock, ConfigProvider config) {
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
            MinionTypeConfig lockedCfg = config.get().type(type.get());
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
        if (manager.minionAt(BlockLocation.of(block)) != null) {
            player.sendMessage(Messages.LOCATION_OCCUPIED);
            return;
        }
        if (manager.tooCloseToOtherMinion(BlockLocation.of(block))) {
            player.sendMessage(Messages.MINION_TOO_CLOSE);
            return;
        }

        MinionTypeConfig cfg = config.get().type(type.get());
        long fuel = items.parseFuel(hand);
        double fuelBoost = items.parseFuelBoost(hand);
        Minion minion = new Minion(
                UUID.randomUUID(), player.getUniqueId(), type.get(),
                items.parseLevel(hand),
                BlockLocation.of(block),
                fuel > 0 ? fuel : cfg.baseFuelTicks(),
                System.currentTimeMillis(), null
        );
        // 恢复拾取前状态：限时燃料加速、产量倍率、永久燃料、累计产出（自包含生成物的完整往返）
        if (fuel > 0 && fuelBoost > 1.0) {
            minion.setFuelBoost(fuelBoost);
        }
        double multBoost = items.parseMultBoost(hand);
        long multTicks = items.parseMultTicks(hand);
        if (multBoost > 1.0 && multTicks > 0) {
            minion.addMultiplier(multBoost, multTicks);
        }
        double permanentBoost = items.parsePermanentBoost(hand);
        if (permanentBoost > 1.0) {
            minion.setPermanentBoost(permanentBoost);
        }
        long produced = items.parseTotalProduced(hand);
        if (produced > 0) {
            minion.addProduced(produced);
        }
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
        // 主副手两次事件都会触发：主手处理完业务后，副手事件直接拦截，
        // 防止原版盔甲架交互在副手路径上产生不一致行为
        if (event.getHand() != EquipmentSlot.HAND) {
            event.setCancelled(true);
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
                returnEmptyContainer(player, hand.getType());
                player.sendMessage(Messages.permanentFuelEquipped((int) ((fuelValue.boost() - 1) * 100)));
            } else if (fuelValue.hasMultiplier()) {
                // 每次只消耗 1 个；弱于当前倍率则不消耗
                if (!minion.addMultiplier(fuelValue.multiplier(), fuelValue.durationTicks())) {
                    player.sendMessage(Messages.multFuelWeak(minion.prodMultiplier()));
                    return;
                }
                hand.setAmount(hand.getAmount() - 1);
                player.sendMessage(Messages.multFuelEquipped(fuelValue.multiplier(), fuelValue.durationTicks() / 20L));
            } else {
                // 每次只消耗 1 个，避免手持整组误操作一次性吃光（GUI 燃料槽仍可整组安装）
                minion.addFuel(fuelValue.durationTicks(), fuelValue.boost());
                hand.setAmount(hand.getAmount() - 1);
                returnEmptyContainer(player, hand.getType());
                player.sendMessage(Messages.fuelAdded((int) ((fuelValue.boost() - 1) * 100)));
            }
            manager.save(minion);
            return;
        }

        manager.openGui(player, minion);
    }

    private void pickup(Player player, Minion minion) {
        ItemStack spawner;
        try {
            spawner = items.createItemFromMinion(minion);
        } catch (Exception e) {
            // 生成物异常时不移除，避免丢数据；留日志便于定位（历史上曾出现静默拾取失败）
            Logs.error("拾取失败（生成物构造异常）: id=" + minion.id() + ", type=" + minion.type(), e);
            player.sendMessage(Component.text("拾取失败：物品生成异常，详情见控制台日志", NamedTextColor.RED));
            return;
        }
        minion.cleanupLayoutBlocks(); // 拾取时还原理想布局摆放的水/岩浆
        manager.remove(minion, player);
        giveOrDrop(player, spawner);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    /** 桶装燃料（岩浆桶）消耗后返还空桶，对齐原版习惯。 */
    private static void returnEmptyContainer(Player player, Material fuelMaterial) {
        if (fuelMaterial == Material.LAVA_BUCKET) {
            giveOrDropStatic(player, new ItemStack(Material.BUCKET, 1));
        }
    }

    private static void giveOrDropStatic(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
