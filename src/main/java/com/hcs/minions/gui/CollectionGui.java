package com.hcs.minions.gui;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionCategory;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.service.CollectionService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.PermissionService;
import com.hcs.minions.upgrade.UpgradeRules;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.ItemRef;
import com.hcs.minions.util.MaterialNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 仆从图鉴 GUI（对齐文档 /minions 的 6 行收藏界面）：
 *
 * <pre>
 * 顶行   0 全部 | 1-6 分类过滤 | 8 关闭
 * 卡片区 9..44（36 格/页，分页展示每种仆从卡片）
 * 底行   48 ◀上页 | 49 进度 x/y | 50 下页▶
 * </pre>
 *
 * <p>卡片数据全部复用现有数据层：已放置/最高等级/总产出来自 {@link MinionManager}，
 * 收集进度来自 {@link CollectionService}，解锁判断来自 {@link PermissionService}。
 * 未解锁类型显示 ??? 与收集进度；点击卡片在聊天栏展示下一级升级配方。</p>
 */
public final class CollectionGui {

    /** 图鉴 GUI 状态容器：翻页/过滤通过重建界面实现（holder 不可变，天然线程安全）。 */
    public record CollectionHolder(UUID playerId, MinionCategory filter, int page) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }

    /** 卡片展示槽（中间 4 行）。 */
    private static final int[] CARD_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44
    };
    private static final int CLOSE_SLOT = 8;
    private static final int PREV_SLOT = 48;
    private static final int PROGRESS_SLOT = 49;
    private static final int NEXT_SLOT = 50;
    private static final int PAGE_SIZE = CARD_SLOTS.length;

    /** 顶行过滤按钮：槽位 -> 分类（null = 全部）。 */
    private static final Map<Integer, MinionCategory> FILTER_SLOTS = Map.of(
            1, MinionCategory.MINING,
            2, MinionCategory.FARMING,
            3, MinionCategory.FORAGING,
            4, MinionCategory.COMBAT,
            5, MinionCategory.FISHING,
            6, MinionCategory.SPECIAL
    );

    private static final Map<MinionCategory, Material> FILTER_ICONS = Map.of(
            MinionCategory.MINING, Material.DIAMOND_PICKAXE,
            MinionCategory.FARMING, Material.GOLDEN_HOE,
            MinionCategory.FORAGING, Material.IRON_AXE,
            MinionCategory.COMBAT, Material.DIAMOND_SWORD,
            MinionCategory.FISHING, Material.FISHING_ROD,
            MinionCategory.SPECIAL, Material.NETHER_STAR
    );

    private final PluginConfig config;
    private final MinionManager manager;
    private final CollectionService collection;
    private final PermissionService permissions;

    public CollectionGui(PluginConfig config, MinionManager manager,
                         CollectionService collection, PermissionService permissions) {
        this.config = config;
        this.manager = manager;
        this.collection = collection;
        this.permissions = permissions;
    }

    /** 打开图鉴（filter 为 null = 全部，page 从 0 起）。 */
    public void open(Player player, MinionCategory filter, int page) {
        Inventory inv = Bukkit.createInventory(
                new CollectionHolder(player.getUniqueId(), filter, Math.max(0, page)), 54,
                GuiText.title("collection-gui.title"));

        List<MinionType> types = visibleTypes(filter);
        int pages = Math.max(1, (types.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int cur = Math.min(page, pages - 1);

        renderFilters(inv, filter);
        renderCards(inv, player, types, cur);
        renderNav(inv, player, cur, pages);
        inv.setItem(CLOSE_SLOT, named(Material.BARRIER, GuiText.title("collection-gui.close.title")));
        player.openInventory(inv);
    }

    /** 当前过滤下的类型列表（保持枚举声明顺序）。 */
    private List<MinionType> visibleTypes(MinionCategory filter) {
        List<MinionType> out = new ArrayList<>();
        for (MinionType type : MinionType.values()) {
            if (filter == null || type.category() == filter) {
                out.add(type);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void renderFilters(Inventory inv, MinionCategory current) {
        inv.setItem(0, filterItem(null, current));
        for (Map.Entry<Integer, MinionCategory> e : FILTER_SLOTS.entrySet()) {
            inv.setItem(e.getKey(), filterItem(e.getValue(), current));
        }
    }

    /** 过滤按钮（当前选中附加发光效果）。 */
    private ItemStack filterItem(MinionCategory category, MinionCategory current) {
        boolean selected = category == current;
        Material icon = category == null ? Material.BOOK : FILTER_ICONS.get(category);
        String name = category == null ? "全部" : category.displayName();
        Map<String, String> v = Map.of("name", name, "sel", selected ? "● " : "");
        ItemStack item = named(icon, GuiText.title("collection-gui.filter.title", v));
        if (selected) {
            ItemMeta meta = item.getItemMeta();
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void renderCards(Inventory inv, Player player, List<MinionType> types, int page) {
        UUID owner = player.getUniqueId();
        Map<MinionType, long[]> stats = statsOf(owner);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < CARD_SLOTS.length; i++) {
            int idx = start + i;
            if (idx >= types.size()) {
                break;
            }
            MinionType type = types.get(idx);
            boolean unlocked = permissions.isUnlocked(player, type);
            if (unlocked) {
                inv.setItem(CARD_SLOTS[i], cardItem(type, stats.getOrDefault(type, new long[3]), owner));
            } else {
                inv.setItem(CARD_SLOTS[i], lockedCard(type, owner));
            }
        }
    }

    /** 已解锁卡片：图标 + 已解锁等级 + 已放置数 + 总产出 + 收集进度 + 下一级配方。 */
    private ItemStack cardItem(MinionType type, long[] stats, UUID owner) {
        MinionTypeConfig cfg = config.type(type);
        if (cfg == null) {
            return named(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        }
        int maxLevel = (int) stats[1];
        long collected = collection.get(owner, cfg.product());
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", cfg.displayName());
        v.put("category", type.category().displayName());
        v.put("tier", maxLevel > 0 ? String.valueOf(maxLevel) : "-");
        v.put("max_tier", String.valueOf(cfg.maxLevel()));
        v.put("placed", String.valueOf(stats[0]));
        v.put("produced", String.valueOf(stats[2]));
        v.put("collected", String.valueOf(collected));
        v.put("product", MaterialNames.of(cfg.product()));
        int row = 1;
        if (maxLevel >= 1 && maxLevel < cfg.maxLevel()) {
            for (Map.Entry<ItemRef, Long> e : cfg.recipeFor(maxLevel).entrySet()) {
                if (row > 3) {
                    break;
                }
                v.put("r" + row, "<dark_gray>· " + e.getKey().displayName() + " ×" + e.getValue());
                row++;
            }
            if (row <= 3 && UpgradeRules.needsPreviousBody(maxLevel, cfg.maxLevel(), config.upgradeRequirePreviousBody())) {
                v.put("r" + row, "<dark_gray>· " + cfg.displayName()
                        + " 等级 " + maxLevel + " ×1");
            }
        }
        return named(type.icon(), GuiText.title("collection-gui.card.title", v),
                GuiText.lore("collection-gui.card.lore", v));
    }

    /** 未解锁卡片：??? + 收集进度（对齐文档的隐藏玩法）。 */
    private ItemStack lockedCard(MinionType type, UUID owner) {
        MinionTypeConfig cfg = config.type(type);
        long collected = cfg == null ? 0 : collection.get(owner, cfg.product());
        long need = cfg == null ? 0 : cfg.unlockAmount();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("category", type.category().displayName());
        v.put("collected", String.valueOf(collected));
        v.put("need", String.valueOf(Math.max(1, need)));
        v.put("product", cfg == null ? "?" : MaterialNames.of(cfg.product()));
        return named(Material.BARRIER, GuiText.title("collection-gui.locked.title", v),
                GuiText.lore("collection-gui.locked.lore", v));
    }

    private void renderNav(Inventory inv, Player player, int page, int pages) {
        if (page > 0) {
            inv.setItem(PREV_SLOT, named(Material.ARROW, GuiText.title("collection-gui.prev.title")));
        }
        if (page < pages - 1) {
            inv.setItem(NEXT_SLOT, named(Material.ARROW, GuiText.title("collection-gui.next.title")));
        }
        Map<String, String> v = Map.of(
                "unlocked", String.valueOf(unlockedCount(player)),
                "total", String.valueOf(MinionType.values().length),
                "page", String.valueOf(page + 1),
                "pages", String.valueOf(pages));
        inv.setItem(PROGRESS_SLOT, named(Material.BOOK,
                GuiText.title("collection-gui.progress.title", v),
                GuiText.lore("collection-gui.progress.lore", v)));
    }

    // ------------------------------------------------------------------
    // 数据聚合（复用现有数据层，不新造存储）
    // ------------------------------------------------------------------

    /** 玩家维度聚合：类型 -> [已放置数, 最高等级, 总产出]。 */
    public Map<MinionType, long[]> statsOf(UUID owner) {
        Map<MinionType, long[]> out = new LinkedHashMap<>();
        for (Minion minion : manager.all()) {
            if (!owner.equals(minion.owner())) {
                continue;
            }
            long[] s = out.computeIfAbsent(minion.type(), k -> new long[3]);
            s[0]++;
            s[1] = Math.max(s[1], minion.level());
            s[2] += minion.totalProduced();
        }
        return out;
    }

    /** 已解锁类型数（底行进度 x/y）。 */
    private int unlockedCount(Player player) {
        int n = 0;
        for (MinionType type : MinionType.values()) {
            if (permissions.isUnlocked(player, type)) {
                n++;
            }
        }
        return n;
    }

    /** 点击卡片后的聊天栏配方详情（下一级配方 + 本体要求）。 */
    public void sendRecipeDetails(Player player, MinionType type) {
        MinionTypeConfig cfg = config.type(type);
        if (cfg == null) {
            return;
        }
        int topLevel = (int) statsOf(player.getUniqueId())
                .getOrDefault(type, new long[3])[1];
        player.sendMessage(GuiText.title("collection-gui.recipe-header.title",
                Map.of("name", cfg.displayName())));
        if (topLevel < 1) {
            player.sendMessage(GuiText.title("collection-gui.recipe-none.title"));
            return;
        }
        if (topLevel >= cfg.maxLevel()) {
            player.sendMessage(GuiText.title("collection-gui.recipe-max.title",
                    Map.of("tier", String.valueOf(cfg.maxLevel()))));
            return;
        }
        for (Map.Entry<ItemRef, Long> e : cfg.recipeFor(topLevel).entrySet()) {
            player.sendMessage(Component.text("· " + e.getKey().displayName() + " ×" + e.getValue())
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (UpgradeRules.needsPreviousBody(topLevel, cfg.maxLevel(), config.upgradeRequirePreviousBody())) {
            player.sendMessage(Component.text("· " + cfg.displayName() + " 等级 " + topLevel + " ×1")
                    .decoration(TextDecoration.ITALIC, false));
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static ItemStack named(Material material, Component name) {
        return named(material, name, null);
    }

    /** 统一物品构造：GuiText 渲染 + 兜底关闭默认斜体。 */
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
