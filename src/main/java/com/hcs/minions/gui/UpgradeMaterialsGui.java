package com.hcs.minions.gui;

import com.hcs.minions.util.Sounds;
import com.hcs.minions.util.EnchantedResource;
import com.hcs.minions.util.GuiLayout;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.MaterialNames;
import net.kyori.adventure.text.Component;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 升级材料总览 GUI（{@code /minion materials}）：
 *
 * <ul>
 *   <li><b>总览页</b>：列出所有仆从升级用到的「附魔资源」（原版材料不显示，因人尽皆知）；
 *       附魔资源按注册数分页（每页卡片数由 layout.materials-gui.card.slots 决定，
 *       默认 28 格，56 种资源分 2 页，底行可翻页）；</li>
 *   <li><b>合成预览页</b>：点击某个附魔资源，3×3 网格按真实数量拆分展示所需基础物品
 *       （合计恰为 ratio），点击成品槽可从背包即时手动压缩 1 个。</li>
 * </ul>
 *
 * <p>总览页纯展示禁止取放；预览页仅成品槽可点击（手动压缩，见
 * {@link com.hcs.minions.gui.UpgradeMaterialsListener}）。
 * 槽位/材质全部经 {@link GuiLayout} 读取，文案全部经 {@link GuiText} 模板渲染（可热重载）。</p>
 */
public final class UpgradeMaterialsGui {

    /** 总览页容器：携带打开者与当前页码（不可变，翻页即重建）。 */
    public static final class OverviewHolder implements InventoryHolder {
        private final UUID playerId;
        private final int page;
        private Inventory inventory;

        public OverviewHolder(UUID playerId, int page) {
            this.playerId = playerId;
            this.page = page;
        }

        public UUID playerId() {
            return playerId;
        }

        public int page() {
            return page;
        }

        void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** 合成预览页容器：携带当前展示的附魔资源 key 与来源页码（返回总览时回到原页）。 */
    public static final class DetailHolder implements InventoryHolder {
        private final UUID playerId;
        private final String resourceKey;
        private final int fromPage;
        private Inventory inventory;

        public DetailHolder(UUID playerId, String resourceKey, int fromPage) {
            this.playerId = playerId;
            this.resourceKey = resourceKey;
            this.fromPage = fromPage;
        }

        public UUID playerId() {
            return playerId;
        }

        public String resourceKey() {
            return resourceKey;
        }

        public int fromPage() {
            return fromPage;
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
    // 布局访问器（gui.yml layout.materials-gui.* 可配，缺省回退内置默认）
    // ------------------------------------------------------------------

    public static int[] cardSlots() {
        return GuiLayout.slots("materials-gui.card.slots");
    }

    public static int prevSlot() {
        return GuiLayout.slot("materials-gui.prev.slot");
    }

    public static int overviewCloseSlot() {
        return GuiLayout.slot("materials-gui.close.slot");
    }

    public static int nextSlot() {
        return GuiLayout.slot("materials-gui.next.slot");
    }

    public static int[] detailGridSlots() {
        return GuiLayout.slots("materials-gui.detail.grid.slots");
    }

    public static int detailArrowSlot() {
        return GuiLayout.slot("materials-gui.detail.arrow.slot");
    }

    public static int detailResultSlot() {
        return GuiLayout.slot("materials-gui.detail.result.slot");
    }

    public static int detailInfoSlot() {
        return GuiLayout.slot("materials-gui.detail.info.slot");
    }

    public static int detailBackSlot() {
        return GuiLayout.slot("materials-gui.detail.back.slot");
    }

    public static int detailCloseSlot() {
        return GuiLayout.slot("materials-gui.detail.close.slot");
    }

    /** 总页数（按卡片槽容量分页）。 */
    public static int pageCount() {
        int per = Math.max(1, cardSlots().length);
        return Math.max(1, (EnchantedResource.all().size() + per - 1) / per);
    }

    // ------------------------------------------------------------------
    // 总览页
    // ------------------------------------------------------------------

    /** 打开升级材料总览页（页码会被钳制到合法范围）。 */
    public static void openOverview(Player player, int page) {
        int pages = pageCount();
        int safePage = Math.floorMod(page, pages);
        List<EnchantedResource> all = new ArrayList<>(EnchantedResource.all());
        int[] cards = cardSlots();
        int start = safePage * cards.length;

        Map<String, String> titleVars = Map.of(
                "page", String.valueOf(safePage + 1),
                "pages", String.valueOf(pages));
        Inventory inv = Bukkit.createInventory(new OverviewHolder(player.getUniqueId(), safePage), 54,
                GuiText.title("materials-gui.title", titleVars));
        if (inv.getHolder() instanceof OverviewHolder holder) {
            holder.attach(inv);
        }
        // 装饰铺底（卡片/翻页/关闭槽随后覆盖）
        ItemStack decor = named(GuiLayout.material("materials-gui.decor.material"), Component.empty(), null);
        for (int s = 0; s < inv.getSize(); s++) {
            inv.setItem(s, decor);
        }
        for (int i = 0; i < cards.length && start + i < all.size(); i++) {
            inv.setItem(cards[i], overviewCard(all.get(start + i)));
        }
        if (pages > 1) {
            inv.setItem(prevSlot(), named(GuiLayout.material("materials-gui.prev.material"),
                    GuiText.title("materials-gui.prev.title"), null));
            inv.setItem(nextSlot(), named(GuiLayout.material("materials-gui.next.material"),
                    GuiText.title("materials-gui.next.title"), null));
        }
        inv.setItem(overviewCloseSlot(), named(GuiLayout.material("materials-gui.close.material"),
                GuiText.title("materials-gui.close.title"), null));
        player.openInventory(inv);
    }

    /** 打开升级材料总览页（第 1 页）。 */
    public static void openOverview(Player player) {
        openOverview(player, 0);
    }

    /** 总览卡片：附魔资源图标（带发光）+ 换算说明 + 点击引导（文案来自 gui.yml）。 */
    private static ItemStack overviewCard(EnchantedResource r) {
        ItemStack item = r.createItem(1);
        ItemMeta meta = item.getItemMeta();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", r.displayName());
        v.put("ratio", String.valueOf(r.ratio()));
        v.put("base", MaterialNames.of(r.base()));
        meta.displayName(GuiText.title("materials-gui.card.title", v));
        meta.lore(GuiText.lore("materials-gui.card.lore", v));
        item.setItemMeta(meta);
        return item;
    }

    /** 槽位 -> 附魔资源（总览页点击反查，按当前页偏移）；非卡片槽返回 null。 */
    public static EnchantedResource resourceAt(int slot, int page) {
        int[] cards = cardSlots();
        int idx = -1;
        for (int i = 0; i < cards.length; i++) {
            if (cards[i] == slot) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return null;
        }
        List<EnchantedResource> all = new ArrayList<>(EnchantedResource.all());
        int index = Math.floorMod(page, pageCount()) * cards.length + idx;
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    // ------------------------------------------------------------------
    // 合成预览页
    // ------------------------------------------------------------------

    /** 打开某附魔资源的合成预览：3×3 网格按真实数量拆分（合计=ratio），成品槽可点击手动压缩。 */
    public static void openDetail(Player player, EnchantedResource r, int fromPage) {
        Map<String, String> titleVars = Map.of("name", r.displayName());
        Inventory inv = Bukkit.createInventory(
                new DetailHolder(player.getUniqueId(), r.resourceKey(), fromPage), 45,
                GuiText.title("materials-gui.detail.title", titleVars));
        if (inv.getHolder() instanceof DetailHolder holder) {
            holder.attach(inv);
        }
        // 装饰铺底
        ItemStack decor = named(GuiLayout.material("materials-gui.detail.decor.material"), Component.empty(), null);
        for (int s = 0; s < inv.getSize(); s++) {
            inv.setItem(s, decor);
        }
        // 3×3 网格按真实数量拆分 ratio（如 160 = 7×18 + 2×17），所见即所需，不再与「需要 160 个」矛盾
        int[] grid = detailGridSlots();
        int ratio = r.ratio();
        int per = Math.min(64, ratio / Math.max(1, grid.length));
        int extra = ratio % Math.max(1, grid.length);
        for (int i = 0; i < grid.length; i++) {
            int amount = per + (i < extra ? 1 : 0);
            inv.setItem(grid[i], amount > 0 ? new ItemStack(r.base(), amount) : null);
        }
        // 信息卡（文案来自 gui.yml；只宣传真实存在的获取途径：手动压缩 / 超级压缩 3000 模块）
        long owned = countBase(player, r);
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", r.displayName());
        v.put("ratio", String.valueOf(ratio));
        v.put("base", MaterialNames.of(r.base()));
        v.put("owned", String.valueOf(owned));
        v.put("can", String.valueOf(owned / ratio));
        inv.setItem(detailInfoSlot(), named(GuiLayout.material("materials-gui.detail.info.material"),
                GuiText.title("materials-gui.detail.info.title", v),
                GuiText.lore("materials-gui.detail.info.lore", v)));
        // 箭头 + 成品（附加点击提示 lore）
        inv.setItem(detailArrowSlot(), named(GuiLayout.material("materials-gui.detail.arrow.material"),
                GuiText.title("materials-gui.detail.arrow.title"), null));
        ItemStack result = r.createItem(1);
        ItemMeta resultMeta = result.getItemMeta();
        List<Component> resultLore = new ArrayList<>(resultMeta.lore() == null ? List.of() : resultMeta.lore());
        resultLore.add(Component.empty());
        resultLore.add(GuiText.title("materials-gui.detail.result-hint", v));
        resultMeta.lore(resultLore);
        result.setItemMeta(resultMeta);
        inv.setItem(detailResultSlot(), result);
        inv.setItem(detailBackSlot(), named(GuiLayout.material("materials-gui.detail.back.material"),
                GuiText.title("materials-gui.back.title"), null));
        inv.setItem(detailCloseSlot(), named(GuiLayout.material("materials-gui.detail.close.material"),
                GuiText.title("materials-gui.close.title"), null));
        player.openInventory(inv);
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
        Sounds.click(player); // 手动压缩成功：轻确认音（ beacon 音保留在压缩成功的强反馈上，见 Sounds.CLICK）
        return true;
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
