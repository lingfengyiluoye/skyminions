package com.hcs.minions.model;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.upgrade.MinionUpgradeType;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.ItemCodec;
import com.hcs.minions.util.ItemRef;
import com.hcs.minions.util.MaterialNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行时仆从。单个 54 格 GUI，对齐 Hypixel SkyBlock Minions 的界面设计：
 * 头颅居中作视觉锚点，信息书含完整产出统计，模块槽位于存储区下方。
 * 所有卡片/按钮文案均由 gui.yml 模板驱动（见 {@link GuiText}），可自定义与热重载。
 *
 * <pre>
 * 顶行  0 燃料 | 3 信息卡 | 4 头颅 | 5 升级 | 7 皮肤
 * 存储  9..44（36 格，按 Tier 解锁）
 * 底行  47/48 模块槽 | 49 收集全部(居中) | 50 自动售卖 | 51 理想布局 | 52 拾取 | 53 关闭
 * </pre>
 */
public final class Minion {

    /** 合成升级全局开关（升级需消耗当前等级仆从本体），MinionsPlugin 加载/重载配置时刷新。 */
    public static volatile boolean requirePreviousBody = true;

    public static final int GUI_SIZE = 54;

    // Hypixel 风格布局：
    //   顶行(0-8)   : 燃料 | 装饰 | 装饰 | 信息卡 | 头颅(居中) | 升级 | 装饰 | 皮肤 | 装饰
    //   存储区(9-44): 36 格（按 Tier 解锁，锁定格灰玻璃）
    //   底行(45-53) : 装饰 | 装饰 | 模块1 | 模块2 | 收集全部(居中) | 自动售卖 | 理想布局 | 拾取 | 关闭
    public static final int[] STORAGE_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44
    };
    public static final int FUEL_SLOT = 0;
    public static final int INFO_SLOT = 3;
    public static final int HEAD_SLOT = 4;
    public static final int UPGRADE_SLOT = 5;
    public static final int SKIN_SLOT = 7;
    public static final int UPGRADE1_SLOT = 47;
    public static final int UPGRADE2_SLOT = 48;
    public static final int COLLECT_SLOT = 49;
    public static final int AUTOSELL_SLOT = 50;
    public static final int LAYOUT_SLOT = 51;
    public static final int PICKUP_SLOT = 52;
    public static final int CLOSE_SLOT = 53;

    private static final int[] DECOR_SLOTS = {1, 2, 6, 8, 45, 46};

    private final UUID id;
    private final UUID owner;
    private final MinionType type;
    private final Inventory storage;

    private volatile int level;
    private volatile BlockLocation location;
    private volatile long fuelTicks;
    private volatile double fuelBoost = 1.0;
    private volatile double permanentBoost = 1.0;
    private volatile boolean autoSell;
    private volatile long totalProduced;
    private volatile long lastActiveEpochMs;
    private volatile String islandId;
    private volatile MinionUpgradeType upgrade1;
    private volatile MinionUpgradeType upgrade2;
    private volatile MinionSkin skin = MinionSkin.DEFAULT;
    /** 盔甲架朝向 yaw（放置时面向放置者）。仅运行时生效，不入存档，重启后重生为默认朝向。 */
    private volatile float facing;
    /** 理想布局开关（目前仅圆石生成器实际生效：自动摆水/岩浆）。运行时状态，不入存档。 */
    private volatile boolean idealLayout;
    /** 理想布局摆放的流体块位置（关闭/拾取时还原为空气）。 */
    private final List<BlockLocation> layoutBlocks = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final AtomicLong nextWorkTick = new AtomicLong();
    private volatile int scanCursor;
    private volatile ArmorStand stand;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /** 上次为观看中的 GUI 刷新状态卡的时间（每秒一次，节流用）。 */
    private volatile long lastGuiRefreshTick;

    public Minion(UUID id, UUID owner, MinionType type, int level,
                  BlockLocation location, long fuelTicks, long lastActiveEpochMs, String islandId) {
        this.id = id;
        this.owner = owner;
        this.type = type;
        this.level = Math.max(1, level);
        this.location = location;
        this.fuelTicks = fuelTicks;
        this.lastActiveEpochMs = lastActiveEpochMs;
        this.islandId = islandId;
        this.storage = Bukkit.createInventory(new StorageHolder(id), GUI_SIZE,
                GuiText.title("title", Map.of("name", type.displayName())));
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public MinionType type() {
        return type;
    }

    public float facing() {
        return facing;
    }

    public void setFacing(float facing) {
        this.facing = facing;
    }

    // ---- 理想布局 ----
    public boolean idealLayout() {
        return idealLayout;
    }

    public void setIdealLayout(boolean on) {
        this.idealLayout = on;
    }

    /** 记录布局摆放的流体块位置。 */
    public void addLayoutBlock(BlockLocation loc) {
        layoutBlocks.add(loc);
    }

    public boolean hasLayoutBlocks() {
        return !layoutBlocks.isEmpty();
    }

    /**
     * 还原布局摆放的流体块（尽力而为）：仅当该位置仍是水/岩浆时才置为空气，
     * 不会破坏玩家后续改动的方块。关闭理想布局与拾取仆从时调用。
     */
    public void cleanupLayoutBlocks() {
        if (layoutBlocks.isEmpty()) {
            return;
        }
        World world = location.bukkitWorld();
        if (world != null) {
            for (BlockLocation bl : layoutBlocks) {
                Block b = world.getBlockAt(bl.x(), bl.y(), bl.z());
                if (b.getType() == Material.WATER || b.getType() == Material.LAVA) {
                    b.setType(Material.AIR, false);
                }
            }
        }
        layoutBlocks.clear();
    }

    public Inventory storage() {
        return storage;
    }

    public int unlockedSlots() {
        // 36 格存储，Tier 1 解锁 9 格，每级 +3 格（对齐 Hypixel 存储随等级成长）
        return Math.min(STORAGE_SLOTS.length, 9 + (level - 1) * 3);
    }

    /** 模块槽解锁 Tier 门槛（对齐 Hypixel 原版：低 Tier 无模块槽）。 */
    private static final int UPGRADE_SLOT1_UNLOCK_TIER = 4;
    private static final int UPGRADE_SLOT2_UNLOCK_TIER = 8;

    /** 当前已解锁的模块槽数量（0~2，随 Tier 增长）。 */
    public int unlockedUpgradeSlots() {
        if (level >= UPGRADE_SLOT2_UNLOCK_TIER) {
            return 2;
        }
        if (level >= UPGRADE_SLOT1_UNLOCK_TIER) {
            return 1;
        }
        return 0;
    }

    public static boolean isStorageSlot(int rawSlot) {
        for (int s : STORAGE_SLOTS) {
            if (s == rawSlot) {
                return true;
            }
        }
        return false;
    }

    // ---- 仓库读写 ----
    public List<ItemStack> storageItems() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack item = storage.getItem(STORAGE_SLOTS[i]);
            if (item != null && item.getType() != Material.AIR && item.getType() != Material.GRAY_STAINED_GLASS_PANE) {
                items.add(item.clone());
            }
        }
        return items;
    }

    public void setStorageItems(List<ItemStack> items) {
        for (int i = 0; i < unlockedSlots(); i++) {
            storage.setItem(STORAGE_SLOTS[i], null);
        }
        addToStorage(items.toArray(new ItemStack[0]));
    }

    private byte[] serializeStorageItems() {
        return ItemCodec.serializeStacks(storageItems());
    }

    public Map<Integer, ItemStack> addToStorage(ItemStack... items) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            ItemStack toAdd = item.clone();
            int remaining = toAdd.getAmount();
            for (int i = 0; i < unlockedSlots() && remaining > 0; i++) {
                ItemStack cur = storage.getItem(STORAGE_SLOTS[i]);
                if (cur != null && cur.isSimilar(toAdd)) {
                    int space = cur.getMaxStackSize() - cur.getAmount();
                    if (space > 0) {
                        int add = Math.min(space, remaining);
                        cur.setAmount(cur.getAmount() + add);
                        remaining -= add;
                    }
                }
            }
            for (int i = 0; i < unlockedSlots() && remaining > 0; i++) {
                ItemStack cur = storage.getItem(STORAGE_SLOTS[i]);
                if (cur == null || cur.getType() == Material.AIR) {
                    int add = Math.min(toAdd.getMaxStackSize(), remaining);
                    ItemStack copy = toAdd.clone();
                    copy.setAmount(add);
                    storage.setItem(STORAGE_SLOTS[i], copy);
                    remaining -= add;
                }
            }
            if (remaining > 0) {
                ItemStack leftover = toAdd.clone();
                leftover.setAmount(remaining);
                leftovers.add(leftover);
            }
        }
        markDirty();
        Map<Integer, ItemStack> map = new HashMap<>();
        for (int i = 0; i < leftovers.size(); i++) {
            map.put(i, leftovers.get(i));
        }
        return map;
    }

    public boolean isStorageFull() {
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack item = storage.getItem(STORAGE_SLOTS[i]);
            if (item == null || item.getType() == Material.AIR) {
                return false;
            }
        }
        return true;
    }

    public List<ItemStack> collectAll() {
        List<ItemStack> items = storageItems();
        for (int i = 0; i < unlockedSlots(); i++) {
            storage.setItem(STORAGE_SLOTS[i], null);
        }
        markDirty();
        return items;
    }

    public boolean consume(ItemRef ref, long amount) {
        long remaining = amount;
        for (int i = 0; i < unlockedSlots() && remaining > 0; i++) {
            ItemStack item = storage.getItem(STORAGE_SLOTS[i]);
            if (!ref.matches(item)) {
                continue;
            }
            int take = (int) Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (item.getAmount() <= 0) {
                storage.setItem(STORAGE_SLOTS[i], null);
            }
        }
        markDirty();
        return remaining == 0;
    }

    /** 原子扣除多材料配方：全部足够才一起扣；返回缺失清单（材料->还差数量），空 map=成功。 */
    public Map<ItemRef, Long> consumeRecipe(Map<ItemRef, Long> recipe) {
        Map<ItemRef, Long> missing = missingRecipe(recipe);
        if (!missing.isEmpty()) {
            return missing; // 原子：任一材料不足则不扣任何材料
        }
        for (Map.Entry<ItemRef, Long> e : recipe.entrySet()) {
            consume(e.getKey(), e.getValue());
        }
        return Map.of();
    }

    /** 只读校验配方是否足够（不扣除）：返回缺失清单，空 map = 全部足够。 */
    public Map<ItemRef, Long> missingRecipe(Map<ItemRef, Long> recipe) {
        Map<ItemRef, Long> missing = new LinkedHashMap<>();
        for (Map.Entry<ItemRef, Long> e : recipe.entrySet()) {
            long owned = countInStorage(e.getKey());
            if (owned < e.getValue()) {
                missing.put(e.getKey(), e.getValue() - owned);
            }
        }
        return missing;
    }

    public void removeItems(List<ItemStack> items) {
        // 用 isSimilar 匹配（含 meta），修复旧实现仅按 getType() 匹配导致
        // 误删同名但 meta 不同物品的问题（P2-1）
        for (ItemStack toRemove : items) {
            if (toRemove == null || toRemove.getType() == Material.AIR) {
                continue;
            }
            int remaining = toRemove.getAmount();
            for (int i = 0; i < unlockedSlots() && remaining > 0; i++) {
                ItemStack item = storage.getItem(STORAGE_SLOTS[i]);
                if (item == null || !item.isSimilar(toRemove)) {
                    continue;
                }
                int take = Math.min(item.getAmount(), remaining);
                item.setAmount(item.getAmount() - take);
                remaining -= take;
                if (item.getAmount() <= 0) {
                    storage.setItem(STORAGE_SLOTS[i], null);
                }
            }
        }
        markDirty();
    }

    public boolean upgrade(MinionTypeConfig cfg) {
        if (level >= cfg.maxLevel()) {
            return false;
        }
        level++;
        markDirty();
        return true;
    }

    // ---- GUI 渲染 ----
    public void refresh(MinionTypeConfig cfg) {
        // 统一深色边框玻璃（顶栏/底栏同色，存储区锁定格用浅灰区分）
        ItemStack decor = named(Material.BLACK_STAINED_GLASS_PANE, Component.empty());
        for (int s : DECOR_SLOTS) {
            storage.setItem(s, decor);
        }
        ItemStack locked = named(Material.GRAY_STAINED_GLASS_PANE,
                GuiText.title("locked-slot.title"), GuiText.lore("locked-slot.lore"));
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack it = storage.getItem(STORAGE_SLOTS[i]);
            if (it != null && it.getType() == Material.GRAY_STAINED_GLASS_PANE) {
                storage.setItem(STORAGE_SLOTS[i], null);
            }
        }
        for (int i = unlockedSlots(); i < STORAGE_SLOTS.length; i++) {
            storage.setItem(STORAGE_SLOTS[i], locked);
        }
        // 燃料槽已按钮化（手持燃料点击即结算），槽内永远只放状态卡，无需担心覆盖真燃料
        storage.setItem(FUEL_SLOT, fuelDisplay());
        storage.setItem(INFO_SLOT, infoItem(cfg));
        storage.setItem(HEAD_SLOT, headItem(cfg));
        storage.setItem(UPGRADE_SLOT, upgradeButton(cfg));
        storage.setItem(SKIN_SLOT, skinItem());
        storage.setItem(UPGRADE1_SLOT, upgradeSlotItem(upgrade1, 1, unlockedUpgradeSlots() >= 1));
        storage.setItem(UPGRADE2_SLOT, upgradeSlotItem(upgrade2, 2, unlockedUpgradeSlots() >= 2));
        storage.setItem(COLLECT_SLOT, collectItem());
        storage.setItem(AUTOSELL_SLOT, autoSellItem());
        storage.setItem(LAYOUT_SLOT, layoutItem(cfg));
        storage.setItem(PICKUP_SLOT, pickupItem());
        storage.setItem(CLOSE_SLOT, named(Material.BARRIER, GuiText.title("close.title")));
    }

    /** 信息卡（Hypixel 信息书风格）：文案来自 gui.yml（info.*），数据以占位符注入；
     *  未配置稀有掉落时稀有行自动隐藏（可选行机制）。 */
    private ItemStack infoItem(MinionTypeConfig cfg) {
        double secondsPerAction = secondsPerAction(cfg, level);
        int side = 2 * cfg.radiusFor(level) + 1;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", cfg.displayName());
        v.put("tier", String.valueOf(level));
        v.put("speed", String.format("%.1f", secondsPerAction));
        v.put("rate", String.valueOf(itemsPerHour(secondsPerAction, cfg.harvestCap())));
        v.put("range", side + "x" + side);
        v.put("storage", String.valueOf(storageCount()));
        v.put("slots", String.valueOf(unlockedSlots()));
        if (cfg.hasRareDrop()) {
            v.put("rare", MaterialNames.of(cfg.rareDrop()));
            v.put("rare_chance", String.format("%.2f", cfg.rareDropChance() * 100));
        }
        v.put("total", String.valueOf(totalProduced));
        v.put("next", String.valueOf(nextWorkSeconds()));
        return named(Material.BOOK, GuiText.title("info.title", v), GuiText.lore("info.lore", v));
    }

    /** 纯函数：指定等级下的单次工作秒数（含燃料加成），供当前/下一级对比。 */
    double secondsPerAction(MinionTypeConfig cfg, int atLevel) {
        return Math.max(0.1, cfg.cooldownTicks() / 20.0 / cfg.efficiencyAt(atLevel) / fuelBoost());
    }

    /** 仓库内现存物品件数（展示用）。 */
    long storageCount() {
        long n = 0;
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack item = storage.getItem(STORAGE_SLOTS[i]);
            if (item != null && item.getType() != Material.AIR) {
                n += item.getAmount();
            }
        }
        return n;
    }

    /** 仓库内指定材料的件数（升级按钮“已有/需要”对比用，按 ItemRef 匹配支持自定义物品）。 */
    public long countInStorage(ItemRef ref) {
        long n = 0;
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack item = storage.getItem(STORAGE_SLOTS[i]);
            if (ref.matches(item)) {
                n += item.getAmount();
            }
        }
        return n;
    }

    /** 仓库内寻找与扣除由调用方谓词匹配的槽位（合成升级消耗仆从本体用），命中返回槽位否则 -1。 */
    public int findSlot(java.util.function.Predicate<ItemStack> matcher) {
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack item = storage.getItem(STORAGE_SLOTS[i]);
            if (item != null && item.getType() != Material.AIR && matcher.test(item)) {
                return STORAGE_SLOTS[i];
            }
        }
        return -1;
    }

    /** 扣除指定槽位 1 件物品。 */
    public void takeOne(int slot) {
        ItemStack item = storage.getItem(slot);
        if (item == null) {
            return;
        }
        item.setAmount(item.getAmount() - 1);
        if (item.getAmount() <= 0) {
            storage.setItem(slot, null);
        }
        markDirty();
    }

    /** 纯函数：按单次工作秒数与单次收获上限估算产出速率（对齐 Hypixel GUI 的 items/hour 展示）。 */
    static long itemsPerHour(double secondsPerAction, int perAction) {
        return Math.round(3600.0 / Math.max(0.1, secondsPerAction) * Math.max(1, perAction));
    }

    private ItemStack headItem(MinionTypeConfig cfg) {
        String ownerName = Bukkit.getOfflinePlayer(owner).getName();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", cfg.displayName());
        v.put("tier", String.valueOf(level));
        v.put("owner", ownerName == null ? "?" : ownerName);
        v.put("skin", skin.displayName());
        return named(type.icon(), GuiText.title("head.title", v), GuiText.lore("head.lore", v));
    }

    /** 燃料槽占位提示物品 PDC 键（与真燃料区分，避免关闭 GUI 时被误当燃料消耗）。 */
    private static final NamespacedKey FUEL_HINT_KEY = new NamespacedKey("skyminions", "fuel_hint");

    /** 燃料槽状态卡（文案来自 gui.yml fuel.*）：限时/永久状态行为可选行按实际状态显隐；
     *  图标随状态变化（无燃料=煤炭，限时=烈焰棒，永久=岩浆桶），空手点击可查看燃料指引。 */
    private ItemStack fuelDisplay() {
        Map<String, String> v = new LinkedHashMap<>();
        if (permanentBoost > 1.0) {
            v.put("perm", String.valueOf((int) ((permanentBoost - 1) * 100)));
        }
        if (fuelTicks > 0) {
            v.put("left", String.valueOf(fuelTicks / 20));
            v.put("timed", String.valueOf((int) ((Math.max(fuelBoost, 1.0) - 1) * 100)));
        }
        if (permanentBoost <= 1.0 && fuelTicks <= 0) {
            v.put("nofuel", "");
        }
        Material icon = permanentBoost > 1.0 ? Material.LAVA_BUCKET
                : fuelTicks > 0 ? Material.BLAZE_ROD : Material.COAL;
        ItemStack hint = named(icon, GuiText.title("fuel.title", v), GuiText.lore("fuel.lore", v));
        ItemMeta meta = hint.getItemMeta();
        meta.getPersistentDataContainer().set(FUEL_HINT_KEY, PersistentDataType.BYTE, (byte) 1);
        hint.setItemMeta(meta);
        return hint;
    }

    /** 判断物品是否为燃料槽占位提示（不是真燃料）。 */
    public static boolean isFuelHint(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(FUEL_HINT_KEY, PersistentDataType.BYTE);
    }

    /** 升级按钮（文案来自 gui.yml）：upgrade（材料充足）/ upgrade-lack（不足）/ upgrade-max（满级）。
     *  多材料配方以 m1~m3 占位符逐行注入，材料名颜色随足够与否变化。 */
    private ItemStack upgradeButton(MinionTypeConfig cfg) {
        if (level >= cfg.maxLevel()) {
            Map<String, String> max = Map.of("tier", String.valueOf(cfg.maxLevel()));
            return named(Material.GOLD_INGOT,
                    GuiText.title("upgrade-max.title", max), GuiText.lore("upgrade-max.lore", max));
        }
        Map<ItemRef, Long> recipe = cfg.recipeFor(level);
        boolean enough = true;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("tier", String.valueOf(level + 1));
        v.put("speed_now", String.format("%.1f", secondsPerAction(cfg, level)));
        v.put("speed_next", String.format("%.1f", secondsPerAction(cfg, level + 1)));
        if (requirePreviousBody) {
            v.put("body", "<dark_gray>· <aqua>仆从本体</aqua> <white>×1</white> <gray>（同类型当前等级）</gray>");
        }
        int row = 1;
        for (Map.Entry<ItemRef, Long> e : recipe.entrySet()) {
            if (row > 3) {
                break; // GUI 最多展示 3 行材料
            }
            long owned = countInStorage(e.getKey());
            if (owned < e.getValue()) {
                enough = false;
            }
            v.put("m" + row, recipeLine(e.getKey(), e.getValue(), owned));
            row++;
        }
        String key = enough ? "upgrade" : "upgrade-lack";
        return named(enough ? Material.GOLD_INGOT : Material.FURNACE,
                GuiText.title(key + ".title", v), GuiText.lore(key + ".lore", v));
    }

    /** 单行材料对比（MiniMessage 片段）：材料名颜色随足够与否变化。 */
    private static String recipeLine(ItemRef ref, long need, long owned) {
        String nameColor = owned >= need ? "<green>" : "<red>";
        return "<dark_gray>· " + nameColor + ref.displayName() + " <white>×" + need + "</white>"
                + " <gray>已有 " + owned;
    }

    private ItemStack upgradeSlotItem(MinionUpgradeType upgrade, int n, boolean unlocked) {
        if (!unlocked) {
            int tier = n == 1 ? UPGRADE_SLOT1_UNLOCK_TIER : UPGRADE_SLOT2_UNLOCK_TIER;
            Map<String, String> v = Map.of("n", String.valueOf(n), "tier", String.valueOf(tier));
            return named(Material.GRAY_STAINED_GLASS_PANE,
                    GuiText.title("module-locked.title", v), GuiText.lore("module-locked.lore", v));
        }
        if (upgrade != null) {
            Map<String, String> v = Map.of("name", upgrade.displayName(), "desc", upgrade.description());
            return named(upgrade.icon(),
                    GuiText.title("module-equipped.title", v), GuiText.lore("module-equipped.lore", v));
        }
        Map<String, String> v = Map.of("n", String.valueOf(n));
        return named(Material.HOPPER,
                GuiText.title("module-empty.title", v), GuiText.lore("module-empty.lore", v));
    }

    private ItemStack collectItem() {
        Map<String, String> v = Map.of("count", String.valueOf(storageCount()));
        return named(Material.GOLD_BLOCK, GuiText.title("collect.title", v), GuiText.lore("collect.lore", v));
    }

    private ItemStack autoSellItem() {
        String key = autoSell ? "autosell-on" : "autosell-off";
        Material icon = autoSell ? Material.EMERALD : Material.GOLD_INGOT;
        return named(icon, GuiText.title(key + ".title"), GuiText.lore(key + ".lore"));
    }

    private ItemStack skinItem() {
        MinionSkin[] skins = MinionSkin.values();
        MinionSkin next = skins[(skin.ordinal() + 1) % skins.length];
        Map<String, String> v = new LinkedHashMap<>();
        v.put("current", skin.displayName());
        v.put("next", next.displayName());
        return named(Material.LEATHER_HELMET, GuiText.title("skin.title", v), GuiText.lore("skin.lore", v));
    }

    private ItemStack layoutItem(MinionTypeConfig cfg) {
        int side = 2 * cfg.radiusFor(level) + 1;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("side", String.valueOf(side));
        if (type == MinionType.COBBLE) {
            // 圆石仆从为可执行开关：可选行显示当前状态（其余类型不显示这两行）
            v.put(idealLayout ? "on" : "off", "");
        }
        return named(Material.MAP, GuiText.title("layout.title", v), GuiText.lore("layout.lore", v));
    }

    private ItemStack pickupItem() {
        return named(Material.ARMOR_STAND, GuiText.title("pickup.title"), GuiText.lore("pickup.lore"));
    }

    /** 统一物品构造：所有文案经 GuiText 渲染；此处兜底关闭原版物品名/Lore 的默认斜体
     *  （Paper 客户端对未显式设置 ITALIC 的 Component 按原版默认样式斜体渲染）。 */
    private ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore.stream().map(Minion::noItalic).toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack named(Material material, Component name) {
        return named(material, name, null);
    }

    // ---- 状态 ----
    public int level() {
        return level;
    }

    public double efficiency(MinionTypeConfig cfg) {
        return cfg.efficiencyAt(level);
    }

    public BlockLocation location() {
        return location;
    }

    public void setLocation(BlockLocation location) {
        this.location = location;
        markDirty();
    }

    public long fuelTicks() {
        return fuelTicks;
    }

    public void setFuelTicks(long fuelTicks) {
        this.fuelTicks = fuelTicks;
        markDirty();
    }

    public double fuelBoost() {
        return Math.max(fuelBoost, permanentBoost);
    }

    public void setFuelBoost(double fuelBoost) {
        this.fuelBoost = fuelBoost;
    }

    /** 永久燃料加速（不衰减，如 Enchanted Lava Bucket）。 */
    public double permanentBoost() {
        return permanentBoost;
    }

    public void setPermanentBoost(double permanentBoost) {
        this.permanentBoost = permanentBoost;
        markDirty();
    }

    /** 添加限时燃料。 */
    public void addFuel(long duration, double boost) {
        this.fuelTicks += duration;
        this.fuelBoost = Math.max(this.fuelBoost, boost);
        markDirty();
    }

    /** 添加永久燃料（取最大加速，不衰减）。 */
    public void addPermanentFuel(double boost) {
        this.permanentBoost = Math.max(this.permanentBoost, boost);
        markDirty();
    }

    public boolean autoSell() {
        return autoSell;
    }

    public void setAutoSell(boolean autoSell) {
        this.autoSell = autoSell;
        markDirty();
    }

    public long totalProduced() {
        return totalProduced;
    }

    public void addProduced(long amount) {
        this.totalProduced += amount;
        markDirty();
    }

    public long lastActiveEpochMs() {
        return lastActiveEpochMs;
    }

    public void setLastActiveEpochMs(long v) {
        this.lastActiveEpochMs = v;
        markDirty();
    }

    public String islandId() {
        return islandId;
    }

    public void setIslandId(String islandId) {
        this.islandId = islandId;
        markDirty();
    }

    // ---- 皮肤 ----
    public MinionSkin skin() {
        return skin;
    }

    public void setSkin(MinionSkin skin) {
        this.skin = skin == null ? MinionSkin.DEFAULT : skin;
        markDirty();
    }

    // ---- 升级模块 ----
    public MinionUpgradeType upgrade1() {
        return upgrade1;
    }

    public void setUpgrade1(MinionUpgradeType type) {
        this.upgrade1 = type;
        markDirty();
    }

    public MinionUpgradeType upgrade2() {
        return upgrade2;
    }

    public void setUpgrade2(MinionUpgradeType type) {
        this.upgrade2 = type;
        markDirty();
    }

    public boolean hasUpgrade(MinionUpgradeType type) {
        return type != null && (type == upgrade1 || type == upgrade2);
    }

    /** 装备模块到第一个空槽，成功返回 true。 */
    public boolean equipUpgrade(MinionUpgradeType type) {
        if (type == null) {
            return false;
        }
        if (upgrade1 == null) {
            upgrade1 = type;
            markDirty();
            return true;
        }
        if (upgrade2 == null) {
            upgrade2 = type;
            markDirty();
            return true;
        }
        return false;
    }

    /** 卸下指定模块槽（1 或 2）的模块并返回，空槽返回 null。 */
    public MinionUpgradeType removeUpgradeSlot(int slot) {
        MinionUpgradeType removed;
        if (slot == 2) {
            removed = upgrade2;
            upgrade2 = null;
        } else {
            removed = upgrade1;
            upgrade1 = null;
        }
        if (removed != null) {
            markDirty();
        }
        return removed;
    }

    public boolean canWorkNow(long nowTick) {
        return nowTick >= nextWorkTick.get();
    }

    public void scheduleNext(long nowTick, int cooldownTicks) {
        nextWorkTick.set(nowTick + Math.max(1, cooldownTicks));
    }

    public int nextWorkInTicks() {
        return Math.max(0, (int) (nextWorkTick.get() - Bukkit.getCurrentTick()));
    }

    public int nextWorkSeconds() {
        return (int) Math.ceil(nextWorkInTicks() / 20.0);
    }

    /** GUI 正被观看且距上次刷新 ≥1 秒时返回 true（供周期任务节流刷新状态卡）。 */
    public boolean shouldRefreshGuiView(long nowTick) {
        return !storage.getViewers().isEmpty() && nowTick - lastGuiRefreshTick >= 20;
    }

    public void markGuiRefreshed(long nowTick) {
        this.lastGuiRefreshTick = nowTick;
    }

    public int scanCursor() {
        return scanCursor;
    }

    public void setScanCursor(int scanCursor) {
        this.scanCursor = scanCursor;
    }

    public ArmorStand stand() {
        return stand;
    }

    public void setStand(ArmorStand stand) {
        this.stand = stand;
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public void markDirty() {
        dirty.set(true);
    }

    public void markClean() {
        dirty.set(false);
    }

    public boolean tryClaimFlush() {
        return dirty.compareAndSet(true, false);
    }

    public MinionData toData() {
        return new MinionData(
                id, owner, type.key(), level, 0L,
                location.world(), location.x(), location.y(), location.z(),
                fuelTicks, lastActiveEpochMs, islandId,
                upgrade1 == null ? null : upgrade1.key(),
                upgrade2 == null ? null : upgrade2.key(),
                skin.key(),
                serializeStorageItems()
        );
    }

    public static Minion fromData(MinionData d) {
        MinionType type = MinionType.fromKey(d.type()).orElse(MinionType.MINER);
        Minion m = new Minion(
                d.id(), d.owner(), type, d.level(),
                new BlockLocation(d.world(), d.x(), d.y(), d.z()),
                d.fuelTicks(), d.lastActiveEpochMs(), d.islandId()
        );
        m.setStorageItems(ItemCodec.deserializeStacks(d.inventory()));
        m.setUpgrade1(MinionUpgradeType.fromKey(d.upgrade1()).orElse(null));
        m.setUpgrade2(MinionUpgradeType.fromKey(d.upgrade2()).orElse(null));
        m.setSkin(MinionSkin.fromKey(d.skin()).orElse(MinionSkin.DEFAULT));
        m.markClean();
        return m;
    }

    public record StorageHolder(UUID minionId) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }
}
