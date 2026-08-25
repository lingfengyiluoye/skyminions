package com.hcs.minions.gui;

import com.hcs.minions.util.GuiLayout;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.MaterialGuide;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 材料合成预览 GUI（纯展示，对齐 Hypixel 的"配方可视化"体验）：
 *
 * <pre>
 * 45 格：中央 3×3 网格按原版摆法摆放原料图标（20-22 / 29-31 / 38-40）
 *        32 箭头 → 34 成品图标
 *         4 信息卡（材料名/原版名/获取途径/页码）
 *         9 ◀ 上一种 | 17 下一种 ▶   44 关闭
 * </pre>
 *
 * <p><b>防刷设计</b>：整个视图（含玩家背包区）所有 Click/Drag 一律取消；
 * 网格与成品均为展示克隆，玩家拿不走、放不进；holder 绑定打开者本人，
 * 非本人操作直接关闭。</p>
 */
public final class RecipePreviewGui {

    /** 预览容器：绑定打开者 + 可循环的材料列表 + 当前下标。 */
    public record PreviewHolder(UUID playerId, List<Material> materials, int index) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }

    public static int[] gridSlots() {
        return GuiLayout.slots("preview.grid.slots");
    }

    public static int arrowSlot() {
        return GuiLayout.slot("preview.arrow.slot");
    }

    public static int resultSlot() {
        return GuiLayout.slot("preview.result.slot");
    }

    public static int infoSlot() {
        return GuiLayout.slot("preview.info.slot");
    }

    public static int prevSlot() {
        return GuiLayout.slot("preview.prev.slot");
    }

    public static int nextSlot() {
        return GuiLayout.slot("preview.next.slot");
    }

    public static int closeSlot() {
        return GuiLayout.slot("preview.close.slot");
    }

    private RecipePreviewGui() {
    }

    /**
     * 打开合成预览。{@code materials} 为本级全部升级材料（guideMaterial 解析结果），
     * {@code index} 指向首个展示的材料；◀ ▶ 循环切换。
     */
    public static void open(Player player, List<Material> materials, int index) {
        if (materials == null || materials.isEmpty()) {
            return;
        }
        int idx = Math.floorMod(index, materials.size());
        Material current = materials.get(idx);
        var guideOpt = MaterialGuide.guide(current);
        String vanillaName = guideOpt.map(MaterialGuide.Guide::vanillaName).orElse(null);
        String howToGet = guideOpt.map(MaterialGuide.Guide::howToGet).orElse(null);
        boolean craftable = guideOpt.map(MaterialGuide.Guide::craftable).orElse(false);

        Map<String, String> v = Map.of(
                "name", com.hcs.minions.util.MaterialNames.of(current),
                "page", String.valueOf(idx + 1),
                "pages", String.valueOf(materials.size()));

        Inventory inv = Bukkit.createInventory(new PreviewHolder(player.getUniqueId(),
                List.copyOf(materials), idx), 45, GuiText.title("preview.title", v));

        // 装饰铺底
        ItemStack decor = named(GuiLayout.material("preview.decor.material"), Component.empty());
        for (int slot : GuiLayout.slots("preview.decor.slots")) {
            inv.setItem(slot, decor);
        }

        // 信息卡
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(GuiText.title("preview.info.vanilla",
                Map.of("vanilla", vanillaName == null ? "-" : vanillaName)));
        infoLore.add(GuiText.title("preview.info.howto",
                Map.of("howto", howToGet == null ? "常规途径获取" : howToGet)));
        inv.setItem(infoSlot(), named(GuiLayout.material("preview.info.material"),
                GuiText.title("preview.info.title"),
                infoLore.stream().map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList()));

        // 3×3 摆法（可合成才有）；不可合成时网格区放"无法合成"占位
        List<Material> grid = MaterialGuide.gridOf(current);
        int[] slots = gridSlots();
        if (craftable) {
            for (int i = 0; i < slots.length && i < grid.size(); i++) {
                Material cell = grid.get(i);
                inv.setItem(slots[i], cell == null ? null : new ItemStack(cell));
            }
        } else {
            inv.setItem(slots[4], named(GuiLayout.material("preview.nocraft.material"),
                    GuiText.title("preview.nocraft.title")));
        }

        // 箭头 + 成品
        inv.setItem(arrowSlot(), named(GuiLayout.material("preview.arrow.material"),
                GuiText.title("preview.arrow.title")));
        inv.setItem(resultSlot(), craftable ? new ItemStack(current) :
                named(GuiLayout.material("preview.nocraft.material"),
                        GuiText.title("preview.nocraft.title")));

        // 切换 / 关闭
        inv.setItem(prevSlot(), named(GuiLayout.material("preview.prev.material"),
                GuiText.title("preview.prev.title")));
        inv.setItem(nextSlot(), named(GuiLayout.material("preview.next.material"),
                GuiText.title("preview.next.title")));
        inv.setItem(closeSlot(), named(GuiLayout.material("preview.close.material"),
                GuiText.title("preview.close.title")));

        player.openInventory(inv);
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }
}
