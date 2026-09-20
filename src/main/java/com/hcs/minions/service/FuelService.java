package com.hcs.minions.service;

import com.hcs.minions.config.FuelEntry;
import com.hcs.minions.util.EnchantedResource;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 燃料定义服务（Hypixel 式双轴制：速度燃料 + 催化剂产量倍率，配置驱动）。
 *
 * <p>数值表来自 config.yml 的 {@code fuels:} / {@code enchanted-fuels:} 段
 * （{@code /minion reload} 热重载）；配置缺失时回退内置默认表。
 * 内置默认与原实现逐项一致，保证升级后行为不变。</p>
 *
 * <p>附魔资源催化剂通过 PDC 身份匹配（{@link EnchantedResource#parse}），
 * 与原版同材质物品彻底区分——附魔煤炭是催化剂载体，不会和散装煤炭混淆。</p>
 */
public final class FuelService {

    /** 内置默认燃料表（config.yml 缺 fuels 段时使用）。 */
    private static FuelEntry.Table defaults() {
        Map<Material, FuelEntry> vanilla = new LinkedHashMap<>();
        // 基础燃料
        vanilla.put(Material.COAL, FuelEntry.vanilla(Material.COAL, 3600L, 1.05, false, 1.0, null));
        vanilla.put(Material.CHARCOAL, FuelEntry.vanilla(Material.CHARCOAL, 3600L, 1.05, false, 1.0, null));
        vanilla.put(Material.COAL_BLOCK, FuelEntry.vanilla(Material.COAL_BLOCK, 18000L, 1.05, false, 1.0, null));
        // 高级燃料
        vanilla.put(Material.LAVA_BUCKET, FuelEntry.vanilla(Material.LAVA_BUCKET, 72000L, 1.25, false, 1.0, Material.BUCKET));
        vanilla.put(Material.BLAZE_ROD, FuelEntry.vanilla(Material.BLAZE_ROD, 21600L, 1.30, false, 1.0, null));
        // 永久燃料（不衰减）
        vanilla.put(Material.MAGMA_CREAM, FuelEntry.vanilla(Material.MAGMA_CREAM, 0L, 1.30, true, 1.0, null));
        vanilla.put(Material.GLOWSTONE_DUST, FuelEntry.vanilla(Material.GLOWSTONE_DUST, 0L, 1.35, true, 1.0, null));
        vanilla.put(Material.DAYLIGHT_DETECTOR, FuelEntry.vanilla(Material.DAYLIGHT_DETECTOR, 0L, 1.25, true, 1.0, null));
        // 催化剂类（产量倍率轴）
        vanilla.put(Material.AMETHYST_SHARD, FuelEntry.vanilla(Material.AMETHYST_SHARD, 36000L, 1.0, false, 1.5, null));
        vanilla.put(Material.BLAZE_POWDER, FuelEntry.vanilla(Material.BLAZE_POWDER, 18000L, 1.0, false, 2.0, null));
        vanilla.put(Material.PHANTOM_MEMBRANE, FuelEntry.vanilla(Material.PHANTOM_MEMBRANE, 9600L, 1.0, false, 3.0, null));
        // 附魔资源催化剂：沉淀在 collection 经济里的高阶催化剂（对齐 Hypixel 附魔面包）
        Map<String, FuelEntry> enchanted = new LinkedHashMap<>();
        enchanted.put("wheat", FuelEntry.enchanted("wheat", "附魔小麦", Material.WHEAT, 36000L, 1.5));
        enchanted.put("carrot", FuelEntry.enchanted("carrot", "附魔胡萝卜", Material.CARROT, 18000L, 2.0));
        enchanted.put("melon", FuelEntry.enchanted("melon", "附魔西瓜", Material.MELON_SLICE, 9600L, 3.0));
        return new FuelEntry.Table(Map.copyOf(vanilla), Map.copyOf(enchanted));
    }

    /** 当前生效的燃料表（volatile 整体替换，热重载安全）。 */
    private static volatile FuelEntry.Table table = defaults();

    private FuelService() {
    }

    /** 热重载：传入配置解析结果；null/空表回退内置默认。 */
    public static void reload(FuelEntry.Table parsed) {
        table = parsed == null || parsed.isEmpty() ? defaults() : parsed;
    }

    /** 当前燃料表（原版 + 附魔资源，有序）。 */
    public static FuelEntry.Table current() {
        return table;
    }

    /** 全部燃料（有序：原版按配置顺序，其后附魔资源），供 GUI/指引遍历。 */
    public static List<FuelEntry> all() {
        List<FuelEntry> out = new ArrayList<>(table.byMaterial().values());
        out.addAll(table.byEnchanted().values());
        return out;
    }

    /** 原版物品是否为燃料。 */
    public static boolean isFuel(Material material) {
        return material != null && table.byMaterial().containsKey(material);
    }

    /** 按物品栈查燃料（附魔资源优先，其次原版材质）。 */
    public static FuelEntry valueOf(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        var enchanted = EnchantedResource.parse(stack);
        if (enchanted.isPresent()) {
            // 附魔资源：命中催化剂表才算燃料；非燃料附魔资源不能当散装原版燃料烧
            return table.byEnchanted().get(enchanted.get().resourceKey());
        }
        return table.byMaterial().get(stack.getType());
    }

    /** 按材质查燃料（仅原版；附魔资源必须走 {@link #valueOf(ItemStack)}）。 */
    public static FuelEntry valueOf(Material material) {
        return material == null ? null : table.byMaterial().get(material);
    }

    // ------------------------------------------------------------------
    // 背包计数/扣除（附魔资源按 PDC 精确匹配，防误吞）
    // ------------------------------------------------------------------

    /** 玩家背包中该燃料的可安装数量。 */
    public static int countOwned(Player player, FuelEntry entry) {
        int n = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, entry)) {
                n += item.getAmount();
            }
        }
        return n;
    }

    /** 从背包扣除指定数量（跨堆叠），成功返回 true。 */
    public static boolean removeOwned(Player player, FuelEntry entry, int amount) {
        if (countOwned(player, entry) < amount) {
            return false;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (!matches(item, entry)) {
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

    /** 物品栈是否就是该燃料（附魔资源比 key，原版比材质且排除附魔资源冒充）。 */
    private static boolean matches(ItemStack item, FuelEntry entry) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (entry.enchantedKey() != null) {
            return EnchantedResource.parse(item)
                    .map(r -> r.resourceKey().equals(entry.enchantedKey()))
                    .orElse(false);
        }
        // 原版燃料：附魔资源不得冒充同材质散装燃料（防催化剂被当煤炭烧）
        return item.getType() == entry.icon() && EnchantedResource.parse(item).isEmpty();
    }
}
