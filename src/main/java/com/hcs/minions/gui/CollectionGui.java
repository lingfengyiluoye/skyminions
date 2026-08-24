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
import com.hcs.minions.util.GuiLayout;
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

    /** 卡片展示槽（中间 4 行，gui.yml layout.collection.card.slots 可配）。 */
    private static int[] cardSlots() {
        return GuiLayout.slots("collection.card.slots");
    }

    /** 顶行过滤按钮：槽位取自配置，按枚举顺序与分类一一对应。 */
    private static Map<Integer, MinionCategory> filterSlots() {
        int[] slots = GuiLayout.slots("collection.filter.slots");
        MinionCategory[] cats = MinionCategory.values();
        Map<Integer, MinionCategory> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(slots.length, cats.length); i++) {
            out.put(slots[i], cats[i]);
        }
        return out;
    }

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
        int pageSize = Math.max(1, cardSlots().length);
        int pages = Math.max(1, (types.size() + pageSize - 1) / pageSize);
        int cur = Math.min(page, pages - 1);

        renderFilters(inv, filter);
        renderCards(inv, player, types, cur);
        renderNav(inv, player, cur, pages);
        inv.setItem(GuiLayout.slot("collection.close.slot"),
                named(GuiLayout.material("collection.close.material"), GuiText.title("collection-gui.close.title")));
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
        inv.setItem(GuiLayout.slot("collection.all.slot"), filterItem(null, current));
        for (Map.Entry<Integer, MinionCategory> e : filterSlots().entrySet()) {
            inv.setItem(e.getKey(), filterItem(e.getValue(), current));
        }
    }

    /** 过滤按钮（当前选中附加发光效果）；图标材质由 layout.collection.icon.* 配置。 */
    private ItemStack filterItem(MinionCategory category, MinionCategory current) {
        boolean selected = category == current;
        Material icon = category == null ? GuiLayout.material("collection.all.material")
                : GuiLayout.material("collection.icon." + category.name().toLowerCase());
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
        int[] slots = cardSlots();
        int start = page * Math.max(1, slots.length);
        for (int i = 0; i < slots.length; i++) {
            int idx = start + i;
            if (idx >= types.size()) {
                break;
            }
            MinionType type = types.get(idx);
            boolean unlocked = permissions.isUnlocked(player, type);
            if (unlocked) {
                inv.setItem(slots[i], cardItem(type, stats.getOrDefault(type, new long[3]), owner));
            } else {
                inv.setItem(slots[i], lockedCard(type, owner));
            }
        }
    }

    /** 已解锁卡片：图标 + 已解锁等级 + 已放置数 + 总产出 + 收集进度 + 下一级配方。 */
    private ItemStack cardItem(MinionType type, long[] stats, UUID owner) {
        MinionTypeConfig cfg = config.type(type);
        if (cfg == null) {
            return named(GuiLayout.material("collection.locked.material"), Component.empty());
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
        return named(GuiLayout.material("collection.locked.material"), GuiText.title("collection-gui.locked.title", v),
                GuiText.lore("collection-gui.locked.lore", v));
    }

    private void renderNav(Inventory inv, Player player, int page, int pages) {
        if (page > 0) {
            inv.setItem(GuiLayout.slot("collection.prev.slot"),
                    named(GuiLayout.material("collection.prev.material"), GuiText.title("collection-gui.prev.title")));
        }
        if (page < pages - 1) {
            inv.setItem(GuiLayout.slot("collection.next.slot"),
                    named(GuiLayout.material("collection.next.material"), GuiText.title("collection-gui.next.title")));
        }
        Map<String, String> v = Map.of(
                "unlocked", String.valueOf(unlockedCount(player)),
                "total", String.valueOf(MinionType.values().length),
                "page", String.valueOf(page + 1),
                "pages", String.valueOf(pages));
        inv.setItem(GuiLayout.slot("collection.progress.slot"),
                named(GuiLayout.material("collection.progress.material"),
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
