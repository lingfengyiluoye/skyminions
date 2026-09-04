package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionBehavior;
import com.hcs.minions.util.Roman;
import com.hcs.minions.util.Textures;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.util.EulerAngle;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 仆从实体服务：以"盔甲架小人"呈现仆从（Hypixel 风格）。
 * 盔甲架 PDC 标记仆从 UUID，交互时据此反查。setPersistent(false) 不入档，
 * 重载时按数据重新生成（引用存于 Minion#stand，isValid() 自愈）。
 */
public final class MinionEntityService {

    private final JavaPlugin plugin;
    private final NamespacedKey minionKey;
    private final ConfigProvider config;
    /** 头顶状态徽标（排版即信息）：工作 ▶ 灰 · 满仓 ⚠ 黄+红字 · 闲置 ⏾ 深灰。 */
    public enum PlateStatus { WORKING, HALTED, DORMANT }

    /** 名牌状态缓存（仆从 id -> 当前徽标），仅状态变化时才改写名牌。 */
    private final Map<UUID, PlateStatus> statusWarnings = new ConcurrentHashMap<>();

    public MinionEntityService(JavaPlugin plugin, ConfigProvider config) {
        this.plugin = plugin;
        this.minionKey = new NamespacedKey(plugin, "minion");
        this.config = config;
    }

    public void spawn(Minion minion) {
        Location loc = minion.location().toLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        // 小盔甲架脚底落在锚点方块上表面（不再 +1 浮空），朝向取放置时记录的 yaw
        Location center = loc.clone().add(0.5, 0.0, 0.5);
        center.setYaw(minion.facing());
        center.setPitch(0);
        ArmorStand stand = loc.getWorld().spawn(center, ArmorStand.class, as -> {
            as.setSmall(true);
            as.setBasePlate(false);
            as.setArms(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setCollidable(false);
            as.setPersistent(false);
            as.customName(baseName(minion));
            as.setCustomNameVisible(true);
            as.getPersistentDataContainer().set(minionKey, PersistentDataType.STRING, minion.id().toString());
            as.setHelmet(head(minion));
            as.setChestplate(leather(Material.LEATHER_CHESTPLATE, color(minion.type().behavior())));
            as.setLeggings(leather(Material.LEATHER_LEGGINGS, color(minion.type().behavior())));
            as.setBoots(leather(Material.LEATHER_BOOTS, color(minion.type().behavior())));
            as.getEquipment().setItemInMainHand(new ItemStack(minion.type().icon()));
            as.setRightArmPose(new EulerAngle(Math.toRadians(-90), 0, 0));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                as.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }
        });
        minion.setStand(stand);
    }

    /** 头顶状态提示（对齐 Hypixel 的状态可读性）：仅在状态变化时改写，需在仆从所在 region 线程调用。 */
    public void refreshStatus(Minion minion, PlateStatus status) {
        PlateStatus prev = statusWarnings.put(minion.id(), status);
        if (prev == status) {
            return;
        }
        ArmorStand stand = minion.stand();
        if (stand == null || !stand.isValid()) {
            return;
        }
        Component base = baseName(minion);
        Component name = switch (status) {
            case WORKING -> Component.text("▶ ", NamedTextColor.GRAY).append(base);
            case HALTED -> Component.text("⚠ ", NamedTextColor.YELLOW).append(base)
                    .append(Component.text(" ⚠仓库已满", NamedTextColor.RED));
            case DORMANT -> {
                base = base.colorIfAbsent(NamedTextColor.DARK_GRAY);
                yield Component.text("⏾ ", NamedTextColor.DARK_GRAY).append(base);
            }
        };
        stand.customName(name);
    }

    /** 名牌基础部分：显示名（金）+ Tier 罗马数字（灰，对齐 Hypixel 名牌），spawn 与状态刷新共用。 */
    private Component baseName(Minion minion) {
        var typeConfig = config.get().type(minion.type());
        String displayName = typeConfig == null ? minion.type().displayName() : typeConfig.displayName();
        return Component.text()
                .append(Component.text(displayName, NamedTextColor.GOLD))
                .append(Component.text(" " + Roman.of(minion.level()), NamedTextColor.GRAY))
                .build();
    }

    /** 皮肤/外观变更后刷新盔甲架（重新设置头盔，无需整只重生）。 */
    public void refreshAppearance(Minion minion) {
        ArmorStand stand = minion.stand();
        if (stand == null || !stand.isValid()) {
            return;
        }
        stand.setHelmet(head(minion));
        stand.setChestplate(leather(Material.LEATHER_CHESTPLATE, color(minion.type().behavior())));
        stand.setLeggings(leather(Material.LEATHER_LEGGINGS, color(minion.type().behavior())));
        stand.setBoots(leather(Material.LEATHER_BOOTS, color(minion.type().behavior())));
    }

    /** 工作手臂挥动动画（每次工作摆动一下，3 tick 后复位避免姿势残留抖动）。 */
    public void swing(Minion minion) {
        ArmorStand stand = minion.stand();
        if (stand == null || !stand.isValid()) {
            return;
        }
        stand.setRightArmPose(new EulerAngle(Math.toRadians(-100 - ThreadLocalRandom.current().nextDouble(50)), 0, 0));
        stand.getScheduler().runDelayed(plugin, task -> {
            if (stand.isValid()) {
                stand.setRightArmPose(new EulerAngle(Math.toRadians(-90), 0, 0));
            }
        }, null, 3);
    }

    public void despawn(Minion minion) {
        statusWarnings.remove(minion.id());
        ArmorStand stand = minion.stand();
        minion.setStand(null);
        if (stand != null && stand.isValid()) {
            stand.remove();
        }
    }

    /** 从点击的盔甲架反查仆从 UUID（PDC）。 */
    public UUID minionIdOf(ArmorStand stand) {
        String id = stand.getPersistentDataContainer().get(minionKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void despawnAll() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getPersistentDataContainer().has(minionKey, PersistentDataType.STRING)) {
                    stand.remove();
                }
            }
        }
    }

    private ItemStack head(Minion minion) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        // 皮肤优先，其次全局默认贴图（base64 纹理，离线模式也可用）
        String texture = minion.skin().texture();
        if (texture == null || texture.isEmpty()) {
            texture = config.get().headTexture();
        }
        if (texture != null && !texture.isEmpty()) {
            // 现代 API：org.bukkit.profile.PlayerProfile + setOwnerProfile（旧 destroystokyo API 已弃用）
            PlayerProfile profile = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), "Minion");
            java.net.URL skinUrl = Textures.skinUrl(texture);
            if (skinUrl != null) {
                profile.getTextures().setSkin(skinUrl);
            }
            meta.setOwnerProfile(profile);
        }
        skull.setItemMeta(meta);
        return skull;
    }

    private ItemStack leather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    private Color color(MinionBehavior behavior) {
        return switch (behavior) {
            case MINING -> Color.GRAY;
            case FARMING -> Color.GREEN;
            case FORAGING -> Color.fromRGB(139, 69, 19);
            case FISHING -> Color.AQUA;
            case COMBAT -> Color.RED;
            case RANCHING -> Color.fromRGB(255, 182, 193);
            case GENERATOR -> Color.fromRGB(120, 120, 120);
        };
    }
}
