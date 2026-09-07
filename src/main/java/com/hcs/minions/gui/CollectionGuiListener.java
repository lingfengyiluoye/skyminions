package com.hcs.minions.gui;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionCategory;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.util.GuiLayout;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.List;
import java.util.Map;

/**
 * 图鉴 GUI 交互：顶行分类过滤、底行翻页、卡片点击直达升级合成台。
 * 翻页/过滤通过 {@link CollectionGui#open} 重建界面实现，无共享可变状态。
 */
public final class CollectionGuiListener implements Listener {

    /** 顶行分类过滤按钮：槽位 -> 分类（槽位由 gui.yml layout 段配置，按枚举顺序对应）。 */
    private static Map<Integer, MinionCategory> filterSlots() {
        int[] slots = GuiLayout.slots("collection.filter.slots");
        MinionCategory[] cats = MinionCategory.values();
        Map<Integer, MinionCategory> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < Math.min(slots.length, cats.length); i++) {
            out.put(slots[i], cats[i]);
        }
        return out;
    }

    private final CollectionGui gui;
    private final MinionManager manager;
    private final ConfigProvider config;
    private final MinionItemService items;

    public CollectionGuiListener(CollectionGui gui, MinionManager manager,
                                 ConfigProvider config, MinionItemService items) {
        this.gui = gui;
        this.manager = manager;
        this.config = config;
        this.items = items;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CollectionGui.CollectionHolder holder)) {
            return;
        }
        event.setCancelled(true); // 图鉴为纯展示界面，禁止取放
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot >= event.getInventory().getSize()) {
            return; // 玩家背包区不拦截（物品仍不可放入，因已 cancel）
        }
        if (slot == GuiLayout.slot("collection.close.slot")) {
            player.closeInventory();
            return;
        }
        if (slot == GuiLayout.slot("collection.all.slot")) {
            gui.open(player, null, 0);
            return;
        }
        Map<Integer, MinionCategory> filters = filterSlots();
        if (filters.containsKey(slot)) {
            gui.open(player, filters.get(slot), 0);
            return;
        }
        if (slot == GuiLayout.slot("collection.prev.slot")) {
            gui.open(player, holder.filter(), holder.page() - 1);
            return;
        }
        if (slot == GuiLayout.slot("collection.next.slot")) {
            gui.open(player, holder.filter(), holder.page() + 1);
            return;
        }
        cardClick(player, holder, slot);
    }

    /** 拖拽保护：图鉴为纯展示界面，任何拖入顶部界面的操作一律取消（防刷/防物品丢失）。 */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof CollectionGui.CollectionHolder)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < event.getInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * 点击卡片：直达该类型的升级合成台（绑定玩家已放置的最低等级仆从）；
     * 未放置该类型或已满级时回退为聊天栏配方详情。
     */
    private void cardClick(Player player, CollectionGui.CollectionHolder holder, int slot) {
        int[] cards = GuiLayout.slots("collection.card.slots");
        int inPage = -1;
        for (int i = 0; i < cards.length; i++) {
            if (cards[i] == slot) {
                inPage = i;
                break;
            }
        }
        if (inPage < 0) {
            return; // 非卡片区槽位
        }
        int index = holder.page() * cards.length + inPage;
        // 必须用与 CollectionGui.renderCards 完全相同的「过滤后」列表还原类型：
        // 渲染是按当前过滤（holder.filter()）后的列表分页的，若此处用未过滤的 MinionType.all()
        // 反查，激活分类过滤时会点到错位的类型（bug）。
        List<MinionType> types = new java.util.ArrayList<>();
        for (MinionType t : MinionType.all()) {
            if (holder.filter() == null || t.category() == holder.filter()) {
                types.add(t);
            }
        }
        if (index < 0 || index >= types.size()) {
            return;
        }
        MinionType clicked = types.get(index);
        openCraftOrDetails(player, clicked);
    }

    /** 有已放置的该类型仆从且未满级 → 打开合成台；否则聊天栏展示配方详情。 */
    private void openCraftOrDetails(Player player, MinionType type) {
        MinionTypeConfig cfg = config.get().type(type);
        if (cfg != null) {
            Minion target = lowestLevelMinionOf(player.getUniqueId(), type);
            if (target != null && target.level() < cfg.maxLevel()) {
                UpgradeCraftGui.open(player, target, items, config);
                return;
            }
        }
        gui.sendRecipeDetails(player, type);
    }

    /** 玩家已放置的指定类型中等级最低的仆从（升级从低到高，取其作为合成台宿主）。 */
    private Minion lowestLevelMinionOf(java.util.UUID owner, MinionType type) {
        Minion best = null;
        for (Minion m : manager.all()) {
            if (!owner.equals(m.owner()) || m.type() != type) {
                continue;
            }
            if (best == null || m.level() < best.level()) {
                best = m;
            }
        }
        return best;
    }
}
