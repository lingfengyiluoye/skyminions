package com.hcs.minions.gui;

import com.hcs.minions.util.EnchantedResource;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.MaterialNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 升级材料总览 GUI（{@code /minion materials}）：
 *
 * <ul>
 *   <li><b>总览页</b>：列出所有仆从升级用到的「附魔资源」（原版材料不显示，因人尽皆知）；</li>
 *   <li><b>合成预览页</b>：点击某个附魔资源，3×3 网格按真实数量拆分展示所需基础物品
 *       （合计恰为 ratio），点击成品槽可从背包即时手动压缩 1 个。</li>
 * </ul>
 *
 * <p>总览页纯展示禁止取放；预览页仅成品槽可点击（手动压缩，见
 * {@link com.hcs.minions.gui.UpgradeMaterialsListener}）。</p>
 */
public final class UpgradeMaterialsGui {

    /** 总览页容器。 */
    public static final class OverviewHolder implements InventoryHolder {
        private final UUID playerId;
        private Inventory inventory;

        public OverviewHolder(UUID playerId) {
            this.playerId = playerId;
        }

        public UUID playerId() {
            return playerId;
        }

        void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** 合成预览页容器：携带当前展示的附魔资源 key。 */
    public static final class DetailHolder implements InventoryHolder {
        private final UUID playerId;
        private final String resourceKey;
        private Inventory inventory;

        public DetailHolder(UUID playerId, String resourceKey) {
            this.playerId = playerId;
            this.resourceKey = resourceKey;
        }

        public UUID playerId() {
            return playerId;
        }

        public String resourceKey() {
            return resourceKey;
        }

        void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private UpgradeMaterialsGui() {
    }

    // ------------------------------------------------------------------
    // 总览页
    // ------------------------------------------------------------------

    /** 展示附魔资源的卡片槽（中间 4 行）。 */
    private static final int[] CARD_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    /** 打开升级材料总览页：列出全部附魔资源（原版材料不列）。 */
    public static void openOverview(Player player) {
        Inventory inv = Bukkit.createInventory(new OverviewHolder(player.getUniqueId()), 54,
                GuiText.title("materials-gui.title"));
        if (inv.getHolder() instanceof OverviewHolder holder) {
            holder.attach(inv);
        }
        ItemStack decor = named(Material.BLUE_STAINED_GLASS_PANE, Component.empty(), null);
        for (int s = 0; s < 54; s++) {
            inv.setItem(s, decor);
        }
        List<EnchantedResource> all = new ArrayList<>(EnchantedResource.all());
        for (int i = 0; i < all.size() && i < CARD_SLOTS.length; i++) {
            inv.setItem(CARD_SLOTS[i], overviewCard(all.get(i)));
        }
        inv.setItem(49, named(Material.BARRIER, GuiText.title("materials-gui.close.title"), null));
        player.openInventory(inv);
    }

    /** 总览卡片：附魔资源图标（带发光）+ 换算说明 + 点击引导。 */
    private static ItemStack overviewCard(EnchantedResource r) {
        ItemStack item = r.createItem(1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(r.displayName(), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("附魔资源 · 仆从升级材料", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(r.ratio() + " × " + MaterialNames.of(r.base()) + " → 1",
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("▶ 点击查看合成方式", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** 槽位 -> 附魔资源（总览页点击反查）；非卡片槽返回 null。 */
    public static EnchantedResource resourceAt(int slot) {
        int idx = -1;
        for (int i = 0; i < CARD_SLOTS.length; i++) {
            if (CARD_SLOTS[i] == slot) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return null;
        }
        List<EnchantedResource> all = new ArrayList<>(EnchantedResource.all());
        return idx < all.size() ? all.get(idx) : null;
    }

    public static int overviewCloseSlot() {
        return 49;
    }

    // ------------------------------------------------------------------
    // 合成预览页
    // ------------------------------------------------------------------

    private static final int[] GRID_SLOTS = {20, 21, 22, 29, 30, 31, 38, 39, 40};
    private static final int ARROW_SLOT = 24;
    private static final int RESULT_SLOT = 25;
    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 40 + 4; // 44
    private static final int DETAIL_CLOSE_SLOT = 36;

    /** 打开某附魔资源的合成预览：3×3 网格按真实数量拆分（合计=ratio），成品槽可点击手动压缩。 */
    public static void openDetail(Player player, EnchantedResource r) {
        Inventory inv = Bukkit.createInventory(new DetailHolder(player.getUniqueId(), r.resourceKey()), 45,
                GuiText.title("materials-gui.detail.title", java.util.Map.of("name", r.displayName())));
        if (inv.getHolder() instanceof DetailHolder holder) {
            holder.attach(inv);
        }
        ItemStack decor = named(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), null);
        for (int s = 0; s < 45; s++) {
            inv.setItem(s, decor);
        }
        // 3×3 网格按真实数量拆分 ratio（如 160 = 7×18 + 2×17），所见即所需，不再与「需要 160 个」矛盾
        int ratio = r.ratio();
        int per = ratio / GRID_SLOTS.length;
        int extra = ratio % GRID_SLOTS.length;
        for (int i = 0; i < GRID_SLOTS.length; i++) {
            inv.setItem(GRID_SLOTS[i], new ItemStack(r.base(), per + (i < extra ? 1 : 0)));
        }
        // 信息卡（只宣传真实存在的获取途径：手动压缩 / 超级压缩 3000 模块）
        List<Component> info = new ArrayList<>();
        info.add(Component.text("需要 " + ratio + " 个 " + MaterialNames.of(r.base()),
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        info.add(Component.text("点击右侧成品：从背包即时压缩 1 个", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        info.add(Component.text("仆从装「超级压缩 3000」模块可自动压缩", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        long owned = countBase(player, r);
        info.add(Component.text("背包现有 " + owned + " ÷ " + ratio + " = 可压缩 " + (owned / ratio) + " 个",
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        inv.setItem(INFO_SLOT, named(Material.KNOWLEDGE_BOOK,
                Component.text("合成 " + r.displayName(), NamedTextColor.GOLD), info));
        // 箭头 + 成品（附加点击提示 lore）
        inv.setItem(ARROW_SLOT, named(Material.ARROW, Component.text("➜ 合成", NamedTextColor.YELLOW), null));
        ItemStack result = r.createItem(1);
        ItemMeta resultMeta = result.getItemMeta();
        List<Component> resultLore = new ArrayList<>(resultMeta.lore() == null ? List.of() : resultMeta.lore());
        resultLore.add(Component.empty());
        resultLore.add(Component.text("▶ 点击从背包压缩 1 个（需 " + ratio + " 个" + MaterialNames.of(r.base()) + "）",
                NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        resultMeta.lore(resultLore);
        result.setItemMeta(resultMeta);
        inv.setItem(RESULT_SLOT, result);
        inv.setItem(BACK_SLOT, named(Material.ARROW,
                GuiText.title("materials-gui.back.title"), null));
        inv.setItem(DETAIL_CLOSE_SLOT, named(Material.BARRIER,
                GuiText.title("materials-gui.close.title"), null));
        player.openInventory(inv);
    }

    public static int detailResultSlot() {
        return RESULT_SLOT;
    }

    /** 统计玩家背包中可压缩的素装基础物品数量（带 meta 的同材质物品不算，防误吞附魔资源/自定义物品）。 */
    private static long countBase(Player player, EnchantedResource r) {
        long count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == r.base() && !item.hasItemMeta()) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * 手动压缩：从玩家背包扣 {@code ratio} 个素装基础物品，换 1 个附魔资源。
     * 先足量校验再扣减（两阶段），不足时不扣任何物品并返回 false（调用方播 deny 音）。
     */
    public static boolean compactFromInventory(Player player, EnchantedResource r) {
        if (countBase(player, r) < r.ratio()) {
            return false;
        }
        int remaining = r.ratio();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack cur = contents[i];
            if (cur == null || cur.getType() != r.base() || cur.hasItemMeta()) {
                continue;
            }
            int take = Math.min(cur.getAmount(), remaining);
            cur.setAmount(cur.getAmount() - take);
            if (cur.getAmount() <= 0) {
                contents[i] = null;
            }
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(r.createItem(1));
        for (ItemStack left : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
        return true;
    }

    public static int detailBackSlot() {
        return BACK_SLOT;
    }

    public static int detailCloseSlot() {
        return DETAIL_CLOSE_SLOT;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        }
        item.setItemMeta(meta);
        return item;
    }
}
