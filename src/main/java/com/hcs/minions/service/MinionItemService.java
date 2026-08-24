package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionSkin;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.upgrade.MinionUpgradeType;
import com.hcs.minions.util.ItemCodec;
import com.hcs.minions.util.Roman;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 仆从生成物工厂：玩家头颅 + PDC 携带类型/等级/燃料/仓库内容。
 * 拾取仆从时把整份数据（含仓库）编码进物品，重新放置即恢复 —— 生成物自包含、跨重启安全。
 */
public final class MinionItemService {

    private final NamespacedKey typeKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey fuelKey;
    private final NamespacedKey fuelBoostKey;
    private final NamespacedKey multBoostKey;
    private final NamespacedKey multTicksKey;
    private final NamespacedKey permanentBoostKey;
    private final NamespacedKey totalProducedKey;
    private final NamespacedKey storageKey;
    private final NamespacedKey upgrade1Key;
    private final NamespacedKey upgrade2Key;
    private final NamespacedKey skinKey;
    private final ConfigProvider config;

    public MinionItemService(JavaPlugin plugin, ConfigProvider config) {
        this.typeKey = new NamespacedKey(plugin, "minion_type");
        this.levelKey = new NamespacedKey(plugin, "minion_level");
        this.fuelKey = new NamespacedKey(plugin, "minion_fuel");
        this.fuelBoostKey = new NamespacedKey(plugin, "minion_fuel_boost");
        this.multBoostKey = new NamespacedKey(plugin, "minion_mult_boost");
        this.multTicksKey = new NamespacedKey(plugin, "minion_mult_ticks");
        this.permanentBoostKey = new NamespacedKey(plugin, "minion_perm_boost");
        this.totalProducedKey = new NamespacedKey(plugin, "minion_total_produced");
        this.storageKey = new NamespacedKey(plugin, "minion_storage");
        this.upgrade1Key = new NamespacedKey(plugin, "minion_upgrade1");
        this.upgrade2Key = new NamespacedKey(plugin, "minion_upgrade2");
        this.skinKey = new NamespacedKey(plugin, "minion_skin");
        this.config = config;
    }

    public ItemStack createItem(MinionType type, int level) {
        MinionTypeConfig cfg = config.get().type(type);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        // 去斜体：Paper 客户端对未显式设置 ITALIC 的物品名/Lore 按原版默认斜体渲染
        // Tier 用罗马数字（对齐 Hypixel：Coal Minion IV）
        meta.displayName(Component.text(cfg.displayName() + " " + Roman.of(level), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("右键方块放置仆从", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(cfg.displayName(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.key());
        meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        meta.getPersistentDataContainer().set(fuelKey, PersistentDataType.LONG, cfg.baseFuelTicks());
        item.setItemMeta(meta);
        // 给生成物头颅也套上默认皮肤纹理，避免显示为空白 Steve 头
        applyHeadTexture(item, MinionSkin.DEFAULT.texture());
        return item;
    }

    /** 为头颅物品设置皮肤纹理（base64）。 */
    private void applyHeadTexture(ItemStack item, String texture) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || texture == null || texture.isEmpty()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
            org.bukkit.profile.PlayerProfile profile = org.bukkit.Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "Minion");
            java.net.URL url = com.hcs.minions.util.Textures.skinUrl(texture);
            if (url != null) {
                profile.getTextures().setSkin(url);
            }
            skullMeta.setOwnerProfile(profile);
            item.setItemMeta(skullMeta);
        }
    }

    /** 拾取仆从时生成：携带完整仓库内容、燃料（含加速）、升级模块、皮肤与累计产出（自包含）。 */
    public ItemStack createItemFromMinion(Minion minion) {
        ItemStack item = createItem(minion.type(), minion.level());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(fuelKey, PersistentDataType.LONG, minion.fuelTicks());
        meta.getPersistentDataContainer().set(fuelBoostKey, PersistentDataType.DOUBLE, minion.fuelBoost());
        if (minion.prodMultiplier() > 1.0) {
            meta.getPersistentDataContainer().set(multBoostKey, PersistentDataType.DOUBLE, minion.prodMultiplier());
            meta.getPersistentDataContainer().set(multTicksKey, PersistentDataType.LONG, minion.multTicks());
        }
        if (minion.permanentBoost() > 1.0) {
            meta.getPersistentDataContainer().set(permanentBoostKey, PersistentDataType.DOUBLE, minion.permanentBoost());
        }
        meta.getPersistentDataContainer().set(totalProducedKey, PersistentDataType.LONG, minion.totalProduced());
        meta.getPersistentDataContainer().set(storageKey, PersistentDataType.BYTE_ARRAY, serialize(minion));
        if (minion.upgrade1() != null) {
            meta.getPersistentDataContainer().set(upgrade1Key, PersistentDataType.STRING, minion.upgrade1().key());
        }
        if (minion.upgrade2() != null) {
            meta.getPersistentDataContainer().set(upgrade2Key, PersistentDataType.STRING, minion.upgrade2().key());
        }
        meta.getPersistentDataContainer().set(skinKey, PersistentDataType.STRING, minion.skin().key());
        item.setItemMeta(meta);
        // 拾取后生成物头颅跟随当前皮肤纹理
        applyHeadTexture(item, minion.skin().texture());
        return item;
    }

    private byte[] serialize(Minion minion) {
        return ItemCodec.serializeStacks(minion.storageItems());
    }

    public boolean isSpawner(ItemStack item) {
        return parseType(item).isPresent();
    }

    public Optional<MinionType> parseType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String key = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return MinionType.fromKey(key);
    }

    public int parseLevel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 1;
        }
        Integer level = item.getItemMeta().getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return level == null || level < 1 ? 1 : level;
    }

    public long parseFuel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Long fuel = item.getItemMeta().getPersistentDataContainer().get(fuelKey, PersistentDataType.LONG);
        return fuel == null ? 0 : fuel;
    }

    /** 拾取前记录的限时燃料加速倍率（缺省 1.0）。 */
    public double parseFuelBoost(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 1.0;
        }
        Double boost = item.getItemMeta().getPersistentDataContainer().get(fuelBoostKey, PersistentDataType.DOUBLE);
        return boost == null ? 1.0 : Math.max(1.0, boost);
    }

    /** 拾取前记录的产量倍率（缺省 1.0）。 */
    public double parseMultBoost(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 1.0;
        }
        Double boost = item.getItemMeta().getPersistentDataContainer().get(multBoostKey, PersistentDataType.DOUBLE);
        return boost == null ? 1.0 : Math.max(1.0, boost);
    }

    /** 拾取前记录的产量倍率剩余 tick（缺省 0）。 */
    public long parseMultTicks(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0L;
        }
        Long ticks = item.getItemMeta().getPersistentDataContainer().get(multTicksKey, PersistentDataType.LONG);
        return ticks == null ? 0L : Math.max(0L, ticks);
    }

    /** 拾取前记录的永久燃料加速倍率（未记录/≤1 返回 1.0 = 无）。 */
    public double parsePermanentBoost(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 1.0;
        }
        Double boost = item.getItemMeta().getPersistentDataContainer().get(permanentBoostKey, PersistentDataType.DOUBLE);
        return boost == null ? 1.0 : Math.max(1.0, boost);
    }

    /** 拾取前记录的累计产出（缺省 0）。 */
    public long parseTotalProduced(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0L;
        }
        Long total = item.getItemMeta().getPersistentDataContainer().get(totalProducedKey, PersistentDataType.LONG);
        return total == null ? 0L : Math.max(0L, total);
    }

    public List<ItemStack> parseStorage(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return List.of();
        }
        byte[] data = item.getItemMeta().getPersistentDataContainer().get(storageKey, PersistentDataType.BYTE_ARRAY);
        return data == null ? List.of() : ItemCodec.deserializeStacks(data);
    }

    public MinionUpgradeType parseUpgrade1(ItemStack item) {
        return parseUpgrade(item, upgrade1Key);
    }

    public MinionUpgradeType parseUpgrade2(ItemStack item) {
        return parseUpgrade(item, upgrade2Key);
    }

    private MinionUpgradeType parseUpgrade(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String k = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return MinionUpgradeType.fromKey(k).orElse(null);
    }

    public MinionSkin parseSkin(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return MinionSkin.DEFAULT;
        }
        String k = item.getItemMeta().getPersistentDataContainer().get(skinKey, PersistentDataType.STRING);
        return MinionSkin.fromKey(k).orElse(MinionSkin.DEFAULT);
    }
}
