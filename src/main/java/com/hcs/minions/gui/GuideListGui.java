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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 材料指南 · 清单页（两级导航的第一级）：
 *
 * <pre>
 * 27 格：中行展示本级全部升级材料（图标 + 需求数量），底部关闭。
 *  · 可合成的原版材料：lore 提示「▶ 点击查看合成方式」，点击进入 {@link RecipePreviewGui}；
 *  · 附魔资源：带附魔光效图标，点击进入 {@link UpgradeMaterialsGui} 压缩预览（可手动压缩）；
 *  · 不可合成的原版材料：红字标注来源，点击无反应（点不开）。
 * </pre>
 *
 * <p><b>条目不按材质合并</b>：附魔煤炭与煤炭同为 COAL 材质，按 Material 去重会把
 * 附魔行整条吞掉（玩家看不到附魔资源需求）。故条目直接按 {@link ItemRef} 保序展示，
 * 预览页循环列表只收非附魔材质，并用 {@code previewIndexOf} 做条目→预览下标映射。</p>
 *
 * <p><b>防刷</b>：整个视图（含玩家背包区）所有点击/拖拽一律取消；
 * holder 绑定打开者本人。</p>
 */
public final class GuideListGui {

    /** 清单容器：绑定打开者 + 条目（保序）+ 预览循环材料 + 条目→预览下标映射；创建后回填真实 {@link Inventory} 满足契约。 */
    public static final class GuideListHolder implements InventoryHolder {
        private final UUID playerId;
        private final List<Map.Entry<ItemRef, Long>> entries;
        private final List<Material> previewMaterials;
        private final int[] previewIndexOf;
        private final int minionLevel;
        private Inventory inventory;

        public GuideListHolder(UUID playerId, List<Map.Entry<ItemRef, Long>> entries,
                               List<Material> previewMaterials, int[] previewIndexOf, int minionLevel) {
            this.playerId = playerId;
            this.entries = entries;
            this.previewMaterials = previewMaterials;
            this.previewIndexOf = previewIndexOf;
            this.minionLevel = minionLevel;
        }

        public UUID playerId() {
            return playerId;
        }

        public List<Map.Entry<ItemRef, Long>> entries() {
            return entries;
        }

        /** 预览页 ◀▶ 循环的材料列表（仅非附魔资源，已去重）。 */
        public List<Material> previewMaterials() {
            return previewMaterials;
        }

        /** 条目下标 -> {@link #previewMaterials()} 下标；-1 = 无图形化预览（附魔资源走专属预览页）。 */
        public int previewIndexOf(int entryIndex) {
            return entryIndex >= 0 && entryIndex < previewIndexOf.length ? previewIndexOf[entryIndex] : -1;
        }

        public int minionLevel() {
            return minionLevel;
        }

        void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
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
     * {@code owned} 为仓内已有数量（按 {@link ItemRef} 统计，可为 null 表示不显示）。
     */
    public static void open(Player player, String typeName, String fromTier, String toTier,
                            Map<ItemRef, Long> recipe, Map<ItemRef, Long> owned,
                            UUID playerId, int minionLevel) {
        // 条目按 ItemRef 保序，不按材质合并（否则附魔资源行会被同材质的原版行吞掉）
        List<Map.Entry<ItemRef, Long>> entries = new ArrayList<>(recipe.entrySet());
        List<Material> previewMaterials = new ArrayList<>();
        int[] previewIndexOf = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            ItemRef ref = entries.get(i).getKey();
            if (ref instanceof ItemRef.EnchantedRef) {
                previewIndexOf[i] = -1; // 附魔资源走 UpgradeMaterialsGui 专属预览
                continue;
            }
            Material mat = ref.guideMaterial();
            int idx = previewMaterials.indexOf(mat);
            if (idx < 0) {
                idx = previewMaterials.size();
                previewMaterials.add(mat);
            }
            previewIndexOf[i] = idx;
        }

        var holder = new GuideListHolder(playerId, List.copyOf(entries),
                List.copyOf(previewMaterials), previewIndexOf, minionLevel);

        Map<String, String> tv = Map.of("name", typeName, "tier", fromTier, "next", toTier);
        Inventory inv = Bukkit.createInventory(holder, 27, GuiText.title("guide-list.title", tv));
        holder.attach(inv);

        ItemStack decor = named(GuiLayout.material("guide-list.decor.material"), Component.empty());
        for (int slot : GuiLayout.slots("guide-list.decor.slots")) {
            inv.setItem(slot, decor);
        }

        int[] slots = materialSlots();
        for (int i = 0; i < entries.size() && i < slots.length; i++) {
            Map.Entry<ItemRef, Long> e = entries.get(i);
            Long have = owned == null ? null : owned.get(e.getKey());
            inv.setItem(slots[i], materialItem(e.getKey(), e.getValue(), have));
        }

        inv.setItem(closeSlot(), named(GuiLayout.material("guide-list.close.material"),
                GuiText.title("guide-list.close.title")));
        player.openInventory(inv);
    }

    /**
     * 单个材料条目物品：名称带需求量；lore 区分可合成（引导点击）、不可合成（来源）
     * 与附魔资源（带附魔光效图标 + 压缩引导）。
     */
    private static ItemStack materialItem(ItemRef ref, long need, Long owned) {
        if (ref instanceof ItemRef.EnchantedRef er) {
            return enchantedItem(er.resource(), need, owned);
        }
        Material mat = ref.guideMaterial();
        var guideOpt = MaterialGuide.guide(mat);
        boolean craftable = guideOpt.map(MaterialGuide.Guide::craftable).orElse(false);
        String vanillaName = guideOpt.map(MaterialGuide.Guide::vanillaName).orElse(null);

        String ctxName = com.hcs.minions.util.MaterialNames.of(mat);
        String showName = (vanillaName == null || vanillaName.equals(ctxName)) ? ctxName : vanillaName;

        Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("material", showName);
        v.put("vanilla", vanillaName == null ? "-" : vanillaName);
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

    /** 附魔资源条目：直接用 {@code createItem} 保留附魔光效与 PDC 身份，名称/lore 按需求渲染。 */
    private static ItemStack enchantedItem(com.hcs.minions.util.EnchantedResource r, long need, Long owned) {
        Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("material", r.displayName());
        v.put("need", String.valueOf(need));
        v.put("owned", owned == null ? "-" : String.valueOf(owned));
        v.put("ratio", String.valueOf(r.ratio()));
        v.put("base", com.hcs.minions.util.MaterialNames.of(r.base()));

        ItemStack item = r.createItem(1);
        var meta = item.getItemMeta();
        meta.displayName(GuiText.title("guide-list.item.name-enchanted", v)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(GuiText.lore("guide-list.item.lore-enchanted", v).stream()
                .map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
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
