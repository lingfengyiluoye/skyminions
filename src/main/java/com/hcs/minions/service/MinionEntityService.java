package com.hcs.minions.service;

import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionType;
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
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 仆从实体服务：以"盔甲架小人"呈现仆从（Hypixel 风格）。
 * 盔甲架 PDC 标记仆从 UUID，交互时据此反查。setPersistent(false) 不入档，
 * 重载时按数据重新生成（引用存于 Minion#stand，isValid() 自愈）。
 */
public final class MinionEntityService {

    private final NamespacedKey minionKey;
    private final PluginConfig config;

    public MinionEntityService(JavaPlugin plugin, PluginConfig config) {
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
            as.customName(Component.text()
                    .append(Component.text(config.type(minion.type()).displayName(), NamedTextColor.GOLD))
                    .append(Component.text(" 等级" + minion.level(), NamedTextColor.GRAY))
                    .build());
            as.setCustomNameVisible(true);
            as.getPersistentDataContainer().set(minionKey, PersistentDataType.STRING, minion.id().toString());
            as.setHelmet(head(minion));
            as.setChestplate(leather(Material.LEATHER_CHESTPLATE, color(minion.type())));
            as.setLeggings(leather(Material.LEATHER_LEGGINGS, color(minion.type())));
            as.setBoots(leather(Material.LEATHER_BOOTS, color(minion.type())));
            as.getEquipment().setItemInMainHand(new ItemStack(minion.type().icon()));
            as.setRightArmPose(new EulerAngle(Math.toRadians(-90), 0, 0));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                as.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }
        });
        minion.setStand(stand);
    }

    /** 皮肤/外观变更后刷新盔甲架（重新设置头盔，无需整只重生）。 */
    public void refreshAppearance(Minion minion) {
        ArmorStand stand = minion.stand();
        if (stand == null || !stand.isValid()) {
            return;
        }
        stand.setHelmet(head(minion));
        stand.setChestplate(leather(Material.LEATHER_CHESTPLATE, color(minion.type())));
        stand.setLeggings(leather(Material.LEATHER_LEGGINGS, color(minion.type())));
        stand.setBoots(leather(Material.LEATHER_BOOTS, color(minion.type())));
    }

    /** 工作手臂挥动动画（每次工作摆动一下）。 */
    public void swing(Minion minion) {
        ArmorStand stand = minion.stand();
        if (stand == null || !stand.isValid()) {
            return;
        }
        stand.setRightArmPose(new EulerAngle(Math.toRadians(-100 - ThreadLocalRandom.current().nextDouble(50)), 0, 0));
    }

    public void despawn(Minion minion) {
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
            texture = config.headTexture();
        }
        if (texture != null && !texture.isEmpty()) {
            // 现代 API：org.bukkit.profile.PlayerProfile + setOwnerProfile（旧 destroystokyo API 已弃用）
            PlayerProfile profile = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), "Minion");
            java.net.URL skinUrl = skinUrl(texture);
            if (skinUrl != null) {
                profile.getTextures().setSkin(skinUrl);
            }
            meta.setOwnerProfile(profile);
        }
        skull.setItemMeta(meta);
        return skull;
    }

    /** 从完整 base64 纹理值解码提取皮肤 URL（现代 API 的 setSkin 只接受 URL）。 */
    private java.net.URL skinUrl(String texture) {
        try {
            String json = new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8);
            int i = json.indexOf("\"url\":\"");
            if (i < 0) {
                return null;
            }
            i += "\"url\":\"".length();
            int j = json.indexOf('"', i);
            if (j < 0) {
                return null;
            }
            return java.net.URI.create(json.substring(i, j)).toURL();
        } catch (Exception e) {
            return null;
        }
    }

    private ItemStack leather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    private Color color(MinionType type) {
        return switch (type) {
            case MINER -> Color.GRAY;
            case FARMER -> Color.GREEN;
            case LUMBERJACK -> Color.fromRGB(139, 69, 19);
            case FISHER -> Color.AQUA;
            case SLAYER -> Color.RED;
            case RANCHER -> Color.fromRGB(255, 182, 193);
            case COBBLE -> Color.fromRGB(120, 120, 120);
        };
    }
}
