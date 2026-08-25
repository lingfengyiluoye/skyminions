package com.hcs.minions.gui;

import com.hcs.minions.util.GuiLayout;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.ItemRef;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 材料指南 · 清单页（两级导航的第一级）：
 *
 * <pre>
 * 27 格：中行展示本级全部升级材料（图标 + 需求数量），底部关闭。
 *  · 可合成的材料：lore 提示「▶ 点击查看合成方式」，点击进入 {@link RecipePreviewGui}；
 *  · 不可合成的材料：红字标注来源，点击无反应（点不开）。
 * </pre>
 *
 * <p><b>防刷</b>：整个视图（含玩家背包区）所有点击/拖拽一律取消；
 * holder 绑定打开者本人。</p>
 */
public final class GuideListGui {

    /** 清单容器：绑定打开者 + 条目（保序）+ 全部材料（供预览页循环）。 */
    public record GuideListHolder(
            UUID playerId,
            List<Map.Entry<ItemRef, Long>> entries,
            List<Material> allMaterials,
            int minionLevel) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }

    /** 材料展示槽位（与 layout.guide-list.material.slots 一致的默认顺序）。 */
    public static int[] materialSlots() {
        return GuiLayout.slots("guide-list.material.slots");
    }

    public static int closeSlot() {
        return GuiLayout.slot("guide-list.close.slot");
    }

    private GuideListGui() {
    }

    /**
     * 打开清单页。{@code recipe} 为该级完整配方（材料 -> 需求量，保序）；
     * {@code owned} 可为 null（不显示已有数量）。
     */
    public static void open(Player player, String typeName, String fromTier, String toTier,
                            Map<ItemRef, Long> recipe, Map<Material, Long> ownedByMaterial,
                            UUID playerId, int minionLevel) {
        // 条目按材质聚合保序（同材质多条合并需求）
        Map<Material, Map.Entry<ItemRef, Long>> ordered = new LinkedHashMap<>();
        List<Material> allMaterials = new ArrayList<>();
        for (Map.Entry<ItemRef, Long> e : recipe.entrySet()) {
            Material mat = e.getKey().guideMaterial();
            allMaterials.add(mat);
            ordered.putIfAbsent(mat, e);
        }

        var holder = new GuideListHolder(playerId,
                List.copyOf(ordered.values()), List.copyOf(allMaterials), minionLevel);

        Map<String, String> tv = Map.of("name", typeName, "tier", fromTier, "next", toTier);
        Inventory inv = Bukkit.createInventory(holder, 27, GuiText.title("guide-list.title", tv));

        ItemStack decor = named(GuiLayout.material("guide-list.decor.material"), Component.empty());
        for (int slot : GuiLayout.slots("guide-list.decor.slots")) {
            inv.setItem(slot, decor);
        }

        int[] slots = materialSlots();
        int i = 0;
        for (Map.Entry<Material, Map.Entry<ItemRef, Long>> e : ordered.entrySet()) {
            if (i >= slots.length) {
                break;
            }
            Material mat = e.getKey();
            long need = e.getValue().getValue();
            Long owned = ownedByMaterial == null ? null : ownedByMaterial.get(mat);
            inv.setItem(slots[i], materialItem(mat, need, owned));
            i++;
        }

        inv.setItem(closeSlot(), named(GuiLayout.material("guide-list.close.material"),
                GuiText.title("guide-list.close.title")));
        player.openInventory(inv);
    }

    /** 单个材料条目物品：名称带需求量；lore 区分可合成（引导点击）与不可合成（来源）。 */
    private static ItemStack materialItem(Material mat, long need, Long owned) {
        var guideOpt = MaterialGuide.guide(mat);
        boolean craftable = guideOpt.map(MaterialGuide.Guide::craftable).orElse(false);
        String vanillaName = guideOpt.map(MaterialGuide.Guide::vanillaName).orElse(null);

        String ctxName = com.hcs.minions.util.MaterialNames.of(mat);
        String showName = (vanillaName == null || vanillaName.equals(ctxName)) ? ctxName : vanillaName;

        Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("material", showName);
        v.put("need", String.valueOf(need));
        v.put("owned", owned == null ? "-" : String.valueOf(owned));
        v.put("source", guideOpt.map(MaterialGuide.Guide::howToGet).orElse("常规途径获取"));

        Component name = GuiText.title(craftable ? "guide-list.item.name-craft" : "guide-list.item.name-nocraft", v);
        String loreKey = craftable ? "guide-list.item.lore-craft" : "guide-list.item.lore-nocraft";
        List<Component> lore = GuiText.lore(loreKey, v);

        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    /** 槽位 -> 条目下标（供监听器反查点击的材料）。 */
    public static int indexOfSlot(int slot) {
        int[] slots = materialSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
