package com.hcs.minions.gui;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.upgrade.UpgradeRules;
import com.hcs.minions.util.GuiLayout;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.ItemRef;
import com.hcs.minions.util.MaterialGuide;
import com.hcs.minions.util.Roman;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 升级合成 GUI（对齐 Hypixel 原版玩法）：打开仆从升级界面，把「上一级本体 + 升级材料」
 * 放入 4×4 合成格，点击右侧产物即合成下一 Tier（原地升级已放置的仆从）。
 *
 * <pre>
 * 54 格：10-13/19-22/28-31/37-40 = 4×4 合成格（自由取放，材料从玩家背包装入）
 *        23 箭头（装饰） | 24 结果槽（材料齐时显示下一级仆从物品，点击合成）
 *        4 配方信息卡（需求/已放对比） | 49 返回 | 其余为装饰玻璃
 * 交互：潜行点击信息卡可从背包一键填充配方材料。
 * 配方本身沿用 config.yml 的 upgrade-recipe / upgrade-cost-growth / upgrade-require-previous-body。
 * </pre>
 */
public final class UpgradeCraftGui {

    /** 合成界面容器：携带所属仆从 id，创建后回填真实 {@link Inventory} 满足契约。 */
    public static final class CraftHolder implements InventoryHolder {
        private final UUID minionId;
        private Inventory inventory;

        public CraftHolder(UUID minionId) {
            this.minionId = minionId;
        }

        public UUID minionId() {
            return minionId;
        }

        void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public static int[] gridSlots() {
        return GuiLayout.slots("craft.grid.slots");
    }

    public static int arrowSlot() {
        return GuiLayout.slot("craft.arrow.slot");
    }

    public static int resultSlot() {
        return GuiLayout.slot("craft.result.slot");
    }

    public static int infoSlot() {
        return GuiLayout.slot("craft.info.slot");
    }

    public static int backSlot() {
        return GuiLayout.slot("craft.back.slot");
    }

    public static int guideSlot() {
        return GuiLayout.slot("craft.guide.slot");
    }

    public static boolean isGridSlot(int slot) {
        for (int s : gridSlots()) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    private UpgradeCraftGui() {
    }

    /** 打开合成界面：绘制装饰/箭头/信息卡，结果槽初始为「材料未集齐」占位。 */
    public static void open(Player player, Minion minion, MinionItemService items, ConfigProvider config) {
        MinionTypeConfig cfg = config.get().type(minion.type());
        Map<String, String> v = Map.of(
                "name", cfg.displayName(),
                "tier", Roman.of(minion.level()));
        Inventory inv = Bukkit.createInventory(new CraftHolder(minion.id()), 54,
                GuiText.title("craft-gui.title", v));
        if (inv.getHolder() instanceof CraftHolder holder) {
            holder.attach(inv); // 回填真实 Inventory，满足 InventoryHolder 契约
        }
        ItemStack decor = named(GuiLayout.material("craft.decor.material"), Component.empty());
        for (int slot : GuiLayout.slots("craft.decor.slots")) {
            inv.setItem(slot, decor);
        }
        inv.setItem(arrowSlot(), named(GuiLayout.material("craft.arrow.material"),
                GuiText.title("craft-gui.arrow.title")));
        inv.setItem(backSlot(), named(GuiLayout.material("craft.back.material"),
                GuiText.title("craft-gui.back.title")));
        inv.setItem(guideSlot(), named(GuiLayout.material("craft.guide.material"),
                GuiText.title("craft-gui.guide.title"), GuiText.lore("craft-gui.guide.lore")));
        inv.setItem(infoSlot(), infoItem(inv, minion, items, cfg, config));
        inv.setItem(resultSlot(), lackItem());
        player.openInventory(inv);
    }

    /** 按当前合成格内容刷新信息卡与结果槽（每次格子变化后调用）。 */
    public static void refresh(Inventory inv, Minion minion, MinionItemService items, ConfigProvider config) {
        MinionTypeConfig cfg = config.get().type(minion.type());
        inv.setItem(infoSlot(), infoItem(inv, minion, items, cfg, config));
        CraftCheck check = validate(inv, minion, items, cfg, config);
        inv.setItem(resultSlot(), check.complete()
                ? items.createItem(minion.type(), minion.level() + 1)
                : lackItem());
    }

    /** 校验合成格内容是否精确匹配升级配方（含本体）。 */
    public static CraftCheck validate(Inventory inv, Minion minion, MinionItemService items,
                                      MinionTypeConfig cfg, ConfigProvider config) {
        Map<ItemRef, Long> recipe = cfg.recipeFor(minion.level());
        boolean needBody = UpgradeRules.needsPreviousBody(
                minion.level(), cfg.maxLevel(), config.get().upgradeRequirePreviousBody());
        Map<ItemRef, Long> remaining = new LinkedHashMap<>(recipe);
        int bodyCount = 0;
        boolean junk = false;
        for (int slot : gridSlots()) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            if (isPreviousBody(item, minion, items)) {
                bodyCount += item.getAmount();
                continue;
            }
            ItemRef matched = null;
            for (ItemRef ref : remaining.keySet()) {
                if (ref.matches(item)) {
                    matched = ref;
                    break;
                }
            }
            if (matched == null) {
                junk = true; // 配方之外的物品
            } else {
                remaining.put(matched, remaining.get(matched) - item.getAmount());
            }
        }
        boolean missingMaterial = remaining.values().stream().anyMatch(v -> v > 0);
        boolean excessMaterial = remaining.values().stream().anyMatch(v -> v < 0);
        boolean bodyOk = needBody ? bodyCount == 1 : bodyCount == 0;
        // 本体多放/不该放也视为「多余物品」，让失败提示准确（而非误报缺材料）
        boolean bodyExcess = needBody ? bodyCount > 1 : bodyCount > 0;
        boolean complete = !junk && !missingMaterial && !excessMaterial && bodyOk;
        Map<ItemRef, Long> missing = new LinkedHashMap<>();
        for (Map.Entry<ItemRef, Long> e : remaining.entrySet()) {
            if (e.getValue() > 0) {
                missing.put(e.getKey(), e.getValue());
            }
        }
        return new CraftCheck(complete, missing, needBody && bodyCount < 1,
                junk || excessMaterial || bodyExcess);
    }

    /** 匹配「当前等级的同类型仆从生成物」（合成升级的上一级本体）。 */
    public static boolean isPreviousBody(ItemStack item, Minion minion, MinionItemService items) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return false;
        }
        return items.parseType(item).map(t -> t == minion.type()).orElse(false)
                && items.parseLevel(item) == minion.level();
    }

    /** 一键填充：从玩家背包聚合装入配方材料（与本体），先归还格内已有物品再填入。 */
    public static void fillFromInventory(Inventory inv, Player player, Minion minion,
                                         MinionItemService items, MinionTypeConfig cfg, ConfigProvider config) {
        Map<ItemRef, Long> recipe = cfg.recipeFor(minion.level());
        boolean needBody = UpgradeRules.needsPreviousBody(
                minion.level(), cfg.maxLevel(), config.get().upgradeRequirePreviousBody());
        // 归还格内已有物品，避免与背包重复计数
        for (int slot : gridSlots()) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                giveOrDrop(player, item);
                inv.setItem(slot, null);
            }
        }
        int[] grid = sortedGridSlots();
        int cursor = 0;
        for (Map.Entry<ItemRef, Long> e : recipe.entrySet()) {
            long need = e.getValue();
            ItemStack[] contents = player.getInventory().getStorageContents();
            // 聚合装箱：同种素装材料合并成整叠再占格（WHEAT×512 只占 8 格而非逐堆逐格），
            // 带 meta 的物品（附魔资源/自定义物品）不可合并，保留原堆单独占格
            long pending = 0;
            List<ItemStack> metaStacks = new ArrayList<>();
            Material plainMaterial = null;
            for (int i = 0; i < contents.length && need > 0; i++) {
                ItemStack cur = contents[i];
                if (cur == null || !e.getKey().matches(cur)) {
                    continue;
                }
                int take = (int) Math.min(cur.getAmount(), need);
                if (isMergeablePlain(cur)) {
                    pending += take;
                    plainMaterial = cur.getType();
                } else {
                    ItemStack part = cur.clone();
                    part.setAmount(take);
                    metaStacks.add(part);
                }
                cur.setAmount(cur.getAmount() - take);
                if (cur.getAmount() <= 0) {
                    contents[i] = null;
                }
                need -= take;
            }
            player.getInventory().setStorageContents(contents);
            if (pending > 0 && plainMaterial != null) {
                int maxStack = plainMaterial.getMaxStackSize();
                while (pending > 0 && cursor < grid.length) {
                    int put = (int) Math.min(pending, maxStack);
                    inv.setItem(grid[cursor++], new ItemStack(plainMaterial, put));
                    pending -= put;
                }
            }
            for (ItemStack part : metaStacks) {
                if (cursor >= grid.length) {
                    giveOrDrop(player, part); // 格位不够：装不下的退回背包，不吞材料
                    continue;
                }
                inv.setItem(grid[cursor++], part);
            }
        }
        if (needBody) {
            ItemStack[] contents = player.getInventory().getStorageContents();
            for (int i = 0; i < contents.length && cursor < grid.length; i++) {
                if (isPreviousBody(contents[i], minion, items)) {
                    ItemStack body = contents[i].clone();
                    body.setAmount(1);
                    contents[i].setAmount(contents[i].getAmount() - 1);
                    if (contents[i].getAmount() <= 0) {
                        contents[i] = null;
                    }
                    player.getInventory().setStorageContents(contents);
                    inv.setItem(grid[cursor++], body);
                    break;
                }
            }
        }
    }

    /** 是否可聚合装箱：无任何 meta 的素装原版物品才允许合并重建（附魔资源 PDC/自定义物品 NBT 必须原样保留）。 */
    private static boolean isMergeablePlain(ItemStack item) {
        return !item.hasItemMeta();
    }

    /** 合成成功后清空合成格（材料已消耗）。 */
    public static void clearGrid(Inventory inv) {
        for (int slot : gridSlots()) {
            inv.setItem(slot, null);
        }
    }

    /** 归还合成格内剩余物品给玩家（关闭界面时兜底）。 */
    public static void returnGridItems(Inventory inv, Player player) {
        for (int slot : gridSlots()) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                giveOrDrop(player, item);
                inv.setItem(slot, null);
            }
        }
    }

    // ------------------------------------------------------------------
    // 卡片渲染
    // ------------------------------------------------------------------

    /** 配方信息卡：逐材料展示 需求/已放，本体行可选（随全局开关）。 */
    private static ItemStack infoItem(Inventory inv, Minion minion, MinionItemService items,
                                      MinionTypeConfig cfg, ConfigProvider config) {
        Map<ItemRef, Long> recipe = cfg.recipeFor(minion.level());
        boolean needBody = UpgradeRules.needsPreviousBody(
                minion.level(), cfg.maxLevel(), config.get().upgradeRequirePreviousBody());
        Map<ItemRef, Long> placed = new LinkedHashMap<>();
        int bodyPlaced = 0;
        for (int slot : gridSlots()) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            if (isPreviousBody(item, minion, items)) {
                bodyPlaced += item.getAmount();
                continue;
            }
            for (ItemRef ref : recipe.keySet()) {
                if (ref.matches(item)) {
                    placed.merge(ref, (long) item.getAmount(), Long::sum);
                    break;
                }
            }
        }
        Map<String, String> v = new LinkedHashMap<>();
        v.put("tier", Roman.of(minion.level()));
        v.put("next", Roman.of(minion.level() + 1));
        int row = 1;
        for (Map.Entry<ItemRef, Long> e : recipe.entrySet()) {
            if (row > 4) {
                break; // 信息卡最多展示 4 行材料
            }
            v.put("m" + row, recipeLine(e.getKey(), e.getValue(), placed.getOrDefault(e.getKey(), 0L)));
            row++;
        }
        if (needBody) {
            v.put("body", recipeBodyLine(bodyPlaced));
        }
        return named(GuiLayout.material("storage.info.material"),
                GuiText.title("craft-gui.info.title", v), GuiText.lore("craft-gui.info.lore", v));
    }

    /** 单行材料对比（MiniMessage 片段）：材料名颜色随是否放够变化，悬浮显示获取指引。 */
    private static String recipeLine(ItemRef ref, long need, long placedCount) {
        String nameColor = placedCount >= need ? "<green>" : "<red>";
        String inner = "<dark_gray>· " + nameColor + ref.displayName() + " <white>×" + need + "</white>"
                + " <gray>已放 " + placedCount;
        return MaterialGuide.wrapHover(ref.guideMaterial(), inner);
    }

    /** 本体行：需要 1 个当前等级的仆从生成物。 */
    private static String recipeBodyLine(int placedCount) {
        String nameColor = placedCount >= 1 ? "<green>" : "<red>";
        return "<dark_gray>· " + nameColor + "仆从本体 <white>×1</white> <gray>已放 " + placedCount;
    }

    /** 结果槽占位（材料未集齐）。 */
    private static ItemStack lackItem() {
        return named(GuiLayout.material("craft.lack.material"),
                GuiText.title("craft-gui.result-lack.title"),
                GuiText.lore("craft-gui.result-lack.lore"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static int[] sortedGridSlots() {
        int[] grid = gridSlots().clone();
        Arrays.sort(grid);
        return grid;
    }

    private static void giveOrDrop(Player player, ItemStack... stacks) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stacks);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private static ItemStack named(Material material, Component name) {
        return named(material, name, null);
    }

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

    /**
     * 合成格校验结果。
     *
     * @param complete    是否精确匹配配方（可合成）
     * @param missing     缺失材料清单（材料 -> 还差数量）
     * @param bodyMissing 需要本体但尚未放入
     * @param excess      存在配方外物品或某种材料超出需求量
     */
    public record CraftCheck(boolean complete, Map<ItemRef, Long> missing, boolean bodyMissing, boolean excess) {
    }
}
