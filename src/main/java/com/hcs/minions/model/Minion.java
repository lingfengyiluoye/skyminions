package com.hcs.minions.model;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.upgrade.MinionUpgradeType;
import com.hcs.minions.util.Bars;
import com.hcs.minions.util.GuiLayout;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.ItemCodec;
import com.hcs.minions.util.ItemRef;
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.MaterialGuide;
import com.hcs.minions.util.MaterialNames;
import com.hcs.minions.util.Roman;
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
 * 运行时仆从。单个 54 格 GUI，对齐 Hypixel SkyBlock Minions 的界面设计。
 * 所有卡片/按钮文案均由 gui.yml 模板驱动（见 {@link GuiText}），可自定义与热重载。
 *
 * <pre>
 * 顶行  0 燃料 | 4 头颅 | 6 信息卡 | 7 皮肤 | 8 升级
 * 左列  9/18/27/36 四个模块槽（燃料下方隔一格竖排），10/19/28/37 间隔列
 * 存储  11..44 去掉左两列后的 28 格（按 Tier 解锁）
 * 底行  49 收集全部(居中) | 50 拾取 | 51 关闭
 * </pre>
 */
public final class Minion {

    public static final int GUI_SIZE = 54;

    // Hypixel 风格布局（槽位/材质均由 gui.yml layout 段驱动，见 {@link GuiLayout}）。
    public static int[] storageSlots() {
        return GuiLayout.slots("storage.slots");
    }

    public static int fuelSlot() {
        return GuiLayout.slot("storage.fuel.slot");
    }

    public static int infoSlot() {
        return GuiLayout.slot("storage.info.slot");
    }

    public static int headSlot() {
        return GuiLayout.slot("storage.head.slot");
    }

    public static int upgradeSlot() {
        return GuiLayout.slot("storage.upgrade.slot");
    }

    public static int skinSlot() {
        return GuiLayout.slot("storage.skin.slot");
    }

    public static int module1Slot() {
        return GuiLayout.slot("storage.module1.slot");
    }

    public static int module2Slot() {
        return GuiLayout.slot("storage.module2.slot");
    }

    public static int module3Slot() {
        return GuiLayout.slot("storage.module3.slot");
    }

    public static int module4Slot() {
        return GuiLayout.slot("storage.module4.slot");
    }

    public static int collectSlot() {
        return GuiLayout.slot("storage.collect.slot");
    }

    public static int pickupSlot() {
        return GuiLayout.slot("storage.pickup.slot");
    }

    public static int closeSlot() {
        return GuiLayout.slot("storage.close.slot");
    }

    private final UUID id;
    private final UUID owner;
    /** 类型 key（持久化身份）；{@link #type()} 每次从注册表解析，热重载即时生效。 */
    private final String typeKey;
    private final Inventory storage;

    private volatile int level;
    private volatile BlockLocation location;
    private volatile long fuelTicks;
    /** 本轮限时燃料已装入总 tick（进度条分母；燃料耗尽/卸下时归零）。 */
    private volatile long fuelTotalTicks;
    private volatile double fuelBoost = 1.0;
    /** 产量倍率燃料（催化剂类）：值与剩余时长，仅在线处理时衰减。 */
    private volatile double multBoost = 1.0;
    private volatile long multTicks;
    /** 本轮催化剂已装入总 tick（进度条分母；倍率归零时清空）。 */
    private volatile long multTotalTicks;
    private volatile double permanentBoost = 1.0;
    private volatile boolean autoSell;
    private volatile long totalProduced;
    private volatile long lastActiveEpochMs;
    private volatile String islandId;
    private volatile MinionUpgradeType upgrade1;
    private volatile MinionUpgradeType upgrade2;
    private volatile MinionUpgradeType upgrade3;
    private volatile MinionUpgradeType upgrade4;
    private volatile MinionSkin skin = MinionSkin.DEFAULT;
    /** 盔甲架朝向 yaw（放置时面向放置者）。仅运行时生效，不入存档，重启后重生为默认朝向。 */
    private volatile float facing;

    private final AtomicLong nextWorkTick = new AtomicLong();
    private volatile int scanCursor;
    private volatile ArmorStand stand;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /** 上次为观看中的 GUI 刷新状态卡的时间（每秒一次，节流用）。 */
    private volatile long lastGuiRefreshTick;
    /** 主人显示名缓存（GUI 渲染每帧都会读，避免重复 getOfflinePlayer 查询）。 */
    private volatile String ownerNameCache;
    /** 运行时状态（信息卡/名牌/诊断共用；由 MinionManager 每周期写入）。 */
    private volatile MinionStatus status = MinionStatus.WORKING;

    /**
     * 标记脏。{@code dirty} 是**唯一**的持久化真相源——仓库层直接扫它收集
     * 待落库名单（{@code CachedMinionRepository#collectDirtyIds}），
     * 不再需要额外的「脏 ID 集合」或通知钩子。
     */
    public void markDirty() {
        dirty.set(true);
    }

    public Minion(UUID id, UUID owner, MinionType type, int level,
                  BlockLocation location, long fuelTicks, long lastActiveEpochMs, String islandId) {
        this.id = id;
        this.owner = owner;
        this.typeKey = type == null ? MinionType.fallback().key() : type.key();
        this.level = Math.max(1, level);
        this.location = location;
        this.fuelTicks = fuelTicks;
        this.lastActiveEpochMs = lastActiveEpochMs;
        this.islandId = islandId;
        StorageHolder holder = new StorageHolder(id);
        this.storage = Bukkit.createInventory(holder, GUI_SIZE,
                GuiText.title("title", Map.of("name", type().displayName())));
        holder.attach(this.storage); // 回填真实 Inventory，满足 InventoryHolder 契约
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    /** 解析当前类型（注册表热重载后自动跟随新定义；未知 key 回退 fallback）。 */
    public MinionType type() {
        return MinionType.fromKey(typeKey).orElse(MinionType.fallback());
    }

    public float facing() {
        return facing;
    }

    public void setFacing(float facing) {
        this.facing = facing;
    }

    // ---- 存储 ----
    public Inventory storage() {
        return storage;
    }

    public int unlockedSlots() {
        // 基础：28 格存储，Tier 1 解锁 7 格，每级 +3 格（左侧让给模块列，右侧为存储区）
        int base = 7 + (level - 1) * 3;
        // 储物箱模块额外解锁存储格（对齐 Hypixel Storage 升级：小/中/大 +6/+12/+18）
        int bonus = 0;
        for (MinionUpgradeType up : new MinionUpgradeType[]{upgrade1, upgrade2, upgrade3, upgrade4}) {
            if (up != null) {
                bonus += up.bonusStorageSlots();
            }
        }
        return Math.min(storageSlots().length, base + bonus);
    }

    /** 模块槽解锁 Tier 门槛（对齐 Hypixel 原版：低 Tier 逐步解锁 4 个模块槽）。 */
    private static final int UPGRADE_SLOT1_UNLOCK_TIER = 3;
    private static final int UPGRADE_SLOT2_UNLOCK_TIER = 6;
    private static final int UPGRADE_SLOT3_UNLOCK_TIER = 9;
    private static final int UPGRADE_SLOT4_UNLOCK_TIER = 11;

    /** 当前已解锁的模块槽数量（0~4，随 Tier 增长）。 */
    public int unlockedUpgradeSlots() {
        if (level >= UPGRADE_SLOT4_UNLOCK_TIER) {
            return 4;
        }
        if (level >= UPGRADE_SLOT3_UNLOCK_TIER) {
            return 3;
        }
        if (level >= UPGRADE_SLOT2_UNLOCK_TIER) {
            return 2;
        }
        if (level >= UPGRADE_SLOT1_UNLOCK_TIER) {
            return 1;
        }
        return 0;
    }

    /** 某模块槽（1~4）的解锁 Tier 门槛。 */
    public static int unlockTierOf(int slot) {
        return switch (slot) {
            case 1 -> UPGRADE_SLOT1_UNLOCK_TIER;
            case 2 -> UPGRADE_SLOT2_UNLOCK_TIER;
            case 3 -> UPGRADE_SLOT3_UNLOCK_TIER;
            default -> UPGRADE_SLOT4_UNLOCK_TIER;
        };
    }

    public static boolean isStorageSlot(int rawSlot) {
        for (int s : storageSlots()) {
            if (s == rawSlot) {
                return true;
            }
        }
        return false;
    }

    // ---- 仓库读写 ----
    public List<ItemStack> storageItems() {
        List<ItemStack> items = new ArrayList<>();
        Material lockedMat = GuiLayout.material("storage.locked.material");
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack item = storage.getItem(storageSlots()[i]);
            if (item != null && item.getType() != Material.AIR && item.getType() != lockedMat) {
                items.add(item.clone());
            }
        }
        return items;
    }

    public void setStorageItems(List<ItemStack> items) {
        for (int i = 0; i < unlockedSlots(); i++) {
            storage.setItem(storageSlots()[i], null);
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
                ItemStack cur = storage.getItem(storageSlots()[i]);
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
                ItemStack cur = storage.getItem(storageSlots()[i]);
                if (cur == null || cur.getType() == Material.AIR) {
                    int add = Math.min(toAdd.getMaxStackSize(), remaining);
                    ItemStack copy = toAdd.clone();
                    copy.setAmount(add);
                    storage.setItem(storageSlots()[i], copy);
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
            ItemStack item = storage.getItem(storageSlots()[i]);
            if (item == null || item.getType() == Material.AIR) {
                return false;
            }
        }
        return true;
    }

    public List<ItemStack> collectAll() {
        List<ItemStack> items = storageItems();
        for (int i = 0; i < unlockedSlots(); i++) {
            storage.setItem(storageSlots()[i], null);
        }
        markDirty();
        return items;
    }

    /**
     * 原子扣除仓库中匹配 {@code ref} 的物品（两阶段：先足量校验再扣减）。
     * 数量不足时<b>不做任何修改</b>并返回 false，杜绝"先扣后验"的部分扣除残留。
     * 仅在仆从所在 region 线程调用（Inventory 独占）。
     */
    public boolean consume(ItemRef ref, long amount) {
        if (amount <= 0 || countInStorage(ref) < amount) {
            return false;
        }
        long remaining = amount;
        for (int i = 0; i < unlockedSlots() && remaining > 0; i++) {
            ItemStack item = storage.getItem(storageSlots()[i]);
            if (!ref.matches(item)) {
                continue;
            }
            int take = (int) Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (item.getAmount() <= 0) {
                storage.setItem(storageSlots()[i], null);
            }
        }
        markDirty();
        return true;
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
                ItemStack item = storage.getItem(storageSlots()[i]);
                if (item == null || !item.isSimilar(toRemove)) {
                    continue;
                }
                int take = Math.min(item.getAmount(), remaining);
                item.setAmount(item.getAmount() - take);
                remaining -= take;
                if (item.getAmount() <= 0) {
                    storage.setItem(storageSlots()[i], null);
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
    public void refresh(MinionTypeConfig cfg, boolean requireBody) {
        // 边框装饰玻璃：支持多色调色板（storage.decor.slots 逐槽轮用 storage.decor.materials 颜色）
        renderDecor();
        Material lockedMat = GuiLayout.material("storage.locked.material");
        ItemStack locked = named(lockedMat,
                GuiText.title("locked-slot.title"), GuiText.lore("locked-slot.lore"));
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack it = storage.getItem(storageSlots()[i]);
            if (it != null && it.getType() == lockedMat) {
                storage.setItem(storageSlots()[i], null);
            }
        }
        for (int i = unlockedSlots(); i < storageSlots().length; i++) {
            storage.setItem(storageSlots()[i], locked);
        }
        // 燃料槽已按钮化（手持燃料点击即结算），槽内永远只放状态卡，无需担心覆盖真燃料
        storage.setItem(fuelSlot(), fuelDisplay());
        storage.setItem(infoSlot(), infoItem(cfg));
        storage.setItem(headSlot(), headItem(cfg));
        storage.setItem(upgradeSlot(), upgradeButton(cfg, requireBody));
        storage.setItem(skinSlot(), skinItem());
        int unlockedModules = unlockedUpgradeSlots();
        storage.setItem(module1Slot(), upgradeSlotItem(upgrade1, 1, unlockedModules >= 1));
        storage.setItem(module2Slot(), upgradeSlotItem(upgrade2, 2, unlockedModules >= 2));
        storage.setItem(module3Slot(), upgradeSlotItem(upgrade3, 3, unlockedModules >= 3));
        storage.setItem(module4Slot(), upgradeSlotItem(upgrade4, 4, unlockedModules >= 4));
        storage.setItem(collectSlot(), collectItem());
        storage.setItem(pickupSlot(), pickupItem());
        storage.setItem(closeSlot(), named(GuiLayout.material("storage.close.material"), GuiText.title("close.title")));
    }

    /** 铺设边框装饰玻璃：多色调色板按槽位顺序循环取色（palette 为空则回退单色 decor.material）。 */
    private void renderDecor() {
        int[] slots = GuiLayout.slots("storage.decor.slots");
        Material[] palette = GuiLayout.materials("storage.decor.materials");
        Material single = GuiLayout.material("storage.decor.material");
        Component blank = Component.empty();
        for (int i = 0; i < slots.length; i++) {
            Material mat = palette.length > 0 ? palette[i % palette.length] : single;
            storage.setItem(slots[i], named(mat, blank));
        }
    }

    /**
     * 信息卡（Hypixel 仪表盘式）：状态首行 → 成对指标 → 一条分区线 → 累计/成长。
     *
     * <p>排版纪律（含标题与分隔线总行数 ≤10，常见形态 6~8 行）：
     * <ul>
     *   <li>状态独立第一行（红=停工/绿=运行），是玩家最常找的信息；</li>
     *   <li>指标两两成行（速度/产出、范围/存储、累计/下次），标签统一两字保证 » 对齐；</li>
     *   <li>进度条必须同维度：存储用「件/件」（当前件数 ÷ 格数×堆叠上限）；</li>
     *   <li>颜色只有四类语义：红=异常、黄=注意/累计、绿=正常、白=中性数值、灰=标签；</li>
     *   <li>可选行按状态显隐：停工时显示恢复提示、隐藏下次工作倒计时（停机时倒计时
     *      不推进，显示反而误导）；无燃料时才显示燃料指引。</li>
     * </ul>
     */
    private ItemStack infoItem(MinionTypeConfig cfg) {
        double secondsPerAction = secondsPerAction(cfg, level);
        // 工作范围含范围扩展模块（与实际工作同口径，否则信息卡永远 5x5、玩家以为模块无效）
        int radius = com.hcs.minions.upgrade.UpgradeService.radiusWithExpander(
                cfg.radiusFor(level), hasUpgrade(MinionUpgradeType.MINION_EXPANDER));
        int side = 2 * radius + 1;
        boolean full = isStorageFull();

        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", cfg.displayName());
        v.put("tier", Roman.of(level));
        v.put("speed", trimNumber(secondsPerAction));
        v.put("rate", String.valueOf(itemsPerHour(secondsPerAction, cfg.harvestCap())));
        v.put("range", side + "x" + side);
        // 存储进度条（同维度：件/件 = 当前件数 ÷ 已解锁格 × 该产物堆叠上限）
        long stored = storageCount();
        long capacity = (long) unlockedSlots() * Math.max(1, cfg.product().getMaxStackSize());
        v.put("storage", Bars.fraction(stored, capacity, "件"));
        v.put("storage_bar", Bars.colored(stored, capacity));
        v.put("total", String.valueOf(totalProduced));
        // 状态行口径唯一：直接读运行时状态（processMinion 每周期写入），
        // 满仓优先级最高——它是最常见也最需要玩家立刻行动的异常
        MinionStatus st = full ? MinionStatus.HALTED_FULL : status();
        v.put("status", "<" + (st.isProducing() ? "green" : "red") + ">" + st.label() + "</" + (st.isProducing() ? "green" : "red") + ">");
        if (full) {
            v.put("halted_tip", GuiText.raw("info.halted-tip", Map.of()));
            // 停机时不显示「下次工作」倒计时（scheduleNext 不推进，显示了反而误导），
            // 但必须注入空串而非省略：省略会让「累计」行被可选行机制一起隐藏
            v.put("next_pair", "");
        } else {
            // 运行中才显示下次工作倒计时
            v.put("next_pair", GuiText.raw("info.next-pair", Map.of("next", String.valueOf(nextWorkSeconds()))));
        }
        if (cfg.hasRareDrop()) {
            v.put("rare", MaterialNames.of(cfg.rareDrop()));
            v.put("rare_chance", String.format("%.2f", cfg.rareDropChance() * 100));
        }
        if (cfg.hasTierGating()) {
            int next = cfg.nextUnlockLevel(level);
            if (next > 0) { // 可选行：还有未解锁的目标档位时展示
                v.put("next_tier", Roman.of(next));
                v.put("unlock_mats", namesOf(cfg.unlocksAt(next)));
            }
        }
        if (permanentBoost() <= 1.0 && fuelTicks() <= 0) {
            // 仅在「完全无燃料」时给一行指引：有燃料时这是噪音
            v.put("hint", GuiText.raw("info.fuel-hint", Map.of()));
        }
        return named(GuiLayout.material("storage.info.material"), GuiText.title("info.title", v), GuiText.lore("info.lore", v));
    }

    /** 数字去多余的 .0（2.0 → 2），保持仪表盘紧凑。 */
    private static String trimNumber(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }

    /** 纯函数：指定等级下的单次工作秒数（含燃料加成），供当前/下一级对比。 */
    double secondsPerAction(MinionTypeConfig cfg, int atLevel) {
        return Math.max(0.1, cfg.cooldownTicksAt(atLevel) / 20.0 / cfg.efficiencyAt(atLevel) / fuelBoost());
    }

    /** 材料中文名列表拼接（顿号分隔），供信息卡解锁档位展示。 */
    private static String namesOf(java.util.Set<Material> mats) {
        StringBuilder sb = new StringBuilder();
        for (Material m : mats) {
            if (!sb.isEmpty()) {
                sb.append('、');
            }
            sb.append(MaterialNames.of(m));
        }
        return sb.toString();
    }

    /** 仓库内现存物品件数（展示用）。 */
    public long storageCount() {
        long n = 0;
        for (int i = 0; i < unlockedSlots(); i++) {
            ItemStack item = storage.getItem(storageSlots()[i]);
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
            ItemStack item = storage.getItem(storageSlots()[i]);
            if (ref.matches(item)) {
                n += item.getAmount();
            }
        }
        return n;
    }

    /** 纯函数：按单次工作秒数与单次收获上限估算产出速率（对齐 Hypixel GUI 的 items/hour 展示）。 */
    static long itemsPerHour(double secondsPerAction, int perAction) {
        return Math.round(3600.0 / Math.max(0.1, secondsPerAction) * Math.max(1, perAction));
    }

    private ItemStack headItem(MinionTypeConfig cfg) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", cfg.displayName());
        v.put("tier", Roman.of(level));
        v.put("owner", ownerName());
        v.put("skin", skin.displayName());
        return named(type().icon(), GuiText.title("head.title", v), GuiText.lore("head.lore", v));
    }

    /**
     * 主人显示名（缓存）：Bukkit.getOfflinePlayer#getName 对未缓存 UUID 可能阻塞，
     * 本方法在 region 线程被 refresh() 反复调用，缓存后每次 GUI 刷新零查询；
     * 解析失败（问号）不缓存，下次重试（玩家数据可能在启动顺序中稍后就绪）。
     */
    private String ownerName() {
        String cached = ownerNameCache;
        if (cached != null) {
            return cached;
        }
        String name = null;
        try {
            name = Bukkit.getOfflinePlayer(owner).getName();
        } catch (RuntimeException e) {
            Logs.warn("查询仆从主人名称失败 owner={}: {}", owner, e.getMessage());
        }
        if (name != null) {
            ownerNameCache = name;
        }
        return name == null ? "?" : name;
    }

    /** 燃料槽占位提示物品 PDC 键（与真燃料区分，避免关闭 GUI 时被误当燃料消耗）。 */
    private static final NamespacedKey FUEL_HINT_KEY = new NamespacedKey("skyminions", "fuel_hint");

    /**
     * 燃料槽状态卡（仪表盘式）：只放状态与进度，不放操作说明书。
     *
     * <pre>
     * 限时 » 45/64 分钟 ▮▮▮▮▮▮▮▯▯▯   （限时燃料：剩余/总量 同维度）
     * 永久 » 加速 +35%              （永久燃料，无时长）
     * 倍率 » ×2.0 · 12/15 分钟 ▮▮▮▮▮▮▮▯▯▯  （催化剂轴）
     * ────────
     * 空手点击查看指引 · 手持燃料点击即生效   （唯一一行常驻提示）
     * </pre>
     *
     * <p>「能装什么/怎么装」的完整说明在空手点击打开的燃料指引里，不常驻卡片。</p>
     */
    private ItemStack fuelDisplay() {
        Map<String, String> v = new LinkedHashMap<>();
        if (fuelTicks > 0) {
            long total = Math.max(fuelTotalTicks, fuelTicks);
            v.put("left", GuiText.raw("fuel.left-line", Map.of(
                    "boost", String.valueOf((int) ((Math.max(fuelBoost, 1.0) - 1) * 100)),
                    "fraction", Bars.fractionTicks(fuelTicks, total),
                    "bar", Bars.colored(fuelTicks, total))));
        }
        if (permanentBoost > 1.0) {
            v.put("perm", GuiText.raw("fuel.perm-line", Map.of(
                    "perm", String.valueOf((int) ((permanentBoost - 1) * 100)))));
        }
        if (multBoost > 1.0) {
            long total = Math.max(multTotalTicks, multTicks);
            v.put("mult", GuiText.raw("fuel.mult-line", Map.of(
                    "mult", "×" + (multBoost == Math.floor(multBoost)
                            ? String.valueOf((long) multBoost) : String.valueOf(multBoost)),
                    "fraction", Bars.fractionTicks(multTicks, total),
                    "bar", Bars.colored(multTicks, total))));
        }
        if (permanentBoost <= 1.0 && fuelTicks <= 0) {
            v.put("nofuel", ""); // 可选行：完全无燃料时显示
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
    private ItemStack upgradeButton(MinionTypeConfig cfg, boolean requireBody) {
        if (level >= cfg.maxLevel()) {
            Map<String, String> max = Map.of("tier", Roman.of(cfg.maxLevel()));
            return named(GuiLayout.material("storage.upgrade.material-max"),
                    GuiText.title("upgrade-max.title", max), GuiText.lore("upgrade-max.lore", max));
        }
        Map<ItemRef, Long> recipe = cfg.recipeFor(level);
        boolean enough = true;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("tier", Roman.of(level + 1));
        v.put("speed_now", String.format("%.1f", secondsPerAction(cfg, level)));
        v.put("speed_next", String.format("%.1f", secondsPerAction(cfg, level + 1)));
        if (requireBody) {
            v.put("body", GuiText.raw("upgrade.body-line", Map.of()));
        }
        int row = 1;
        int shown = 0;
        // enough 必须按完整配方判定（不是只看展示行）：否则材料种类 >4 时按钮显示
        // "材料充足" 但实际缺料，玩家点了打不开/合成失败，属于误导性 UI
        for (Map.Entry<ItemRef, Long> e : recipe.entrySet()) {
            long owned = countInStorage(e.getKey());
            if (owned < e.getValue()) {
                enough = false;
            }
            if (row <= 4) {
                v.put("m" + row, recipeLine(e.getKey(), e.getValue(), owned));
                shown++;
            }
            row++;
        }
        if (shown < recipe.size()) {
            // 超出 4 行的材料在按钮上不可见：显式告知总数，避免玩家以为配方只有看到的几项
            v.put("more", GuiText.raw("upgrade.more-line",
                    Map.of("count", String.valueOf(recipe.size()))));
        }
        String key = enough ? "upgrade" : "upgrade-lack";
        return named(GuiLayout.material("storage.upgrade.material-" + (enough ? "ok" : "lack")),
                GuiText.title(key + ".title", v), GuiText.lore(key + ".lore", v));
    }

    /** 单行材料对比（MiniMessage 片段，模板来自 gui.yml）：材料名颜色随足够与否变化。 */
    private static String recipeLine(ItemRef ref, long need, long owned) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("color", owned >= need ? "<green>" : "<red>");
        v.put("name", ref.displayName());
        v.put("need", String.valueOf(need));
        v.put("owned", String.valueOf(owned));
        return MaterialGuide.wrapHover(ref.guideMaterial(), GuiText.raw("upgrade.material-line", v));
    }

    private ItemStack upgradeSlotItem(MinionUpgradeType upgrade, int n, boolean unlocked) {
        if (!unlocked) {
            Map<String, String> v = Map.of("n", String.valueOf(n), "tier", Roman.of(unlockTierOf(n)));
            return named(GuiLayout.material("storage.locked.material"),
                    GuiText.title("module-locked.title", v), GuiText.lore("module-locked.lore", v));
        }
        if (upgrade != null) {
            Map<String, String> v = Map.of("name", upgrade.displayName(), "desc", upgrade.description());
            return named(upgrade.icon(),
                    GuiText.title("module-equipped.title", v), GuiText.lore("module-equipped.lore", v));
        }
        Map<String, String> v = Map.of("n", String.valueOf(n));
        return named(GuiLayout.material("storage.module-empty.material"),
                GuiText.title("module-empty.title", v), GuiText.lore("module-empty.lore", v));
    }

    private ItemStack collectItem() {
        Map<String, String> v = Map.of("count", String.valueOf(storageCount()));
        return named(GuiLayout.material("storage.collect.material"), GuiText.title("collect.title", v), GuiText.lore("collect.lore", v));
    }

    private ItemStack skinItem() {
        MinionSkin[] skins = MinionSkin.values();
        MinionSkin next = skins[(skin.ordinal() + 1) % skins.length];
        Map<String, String> v = new LinkedHashMap<>();
        v.put("current", skin.displayName());
        v.put("next", next.displayName());
        return named(GuiLayout.material("storage.skin.material"), GuiText.title("skin.title", v), GuiText.lore("skin.lore", v));
    }

    private ItemStack pickupItem() {
        return named(GuiLayout.material("storage.pickup.material"), GuiText.title("pickup.title"), GuiText.lore("pickup.lore"));
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

    public long fuelTicks() {
        return fuelTicks;
    }

    /** 本轮限时燃料已装入总 tick（GUI 进度条分母；0 = 无燃料）。 */
    public long fuelTotalTicks() {
        return fuelTotalTicks;
    }

    public void setFuelTicks(long fuelTicks) {
        this.fuelTicks = fuelTicks;
        if (fuelTicks <= 0) {
            // 燃料见底/被卸下：总量同步归零，避免下次装入时分数带上一轮的残差
            this.fuelTotalTicks = 0;
        }
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

    /** 添加限时燃料。续期时按剩余时长加权平均加速，避免低级燃料白蹭高级加成。 */
    public void addFuel(long duration, double boost) {
        if (duration <= 0) {
            return;
        }
        if (fuelTicks <= 0) {
            this.fuelBoost = boost;
            this.fuelTotalTicks = duration;
        } else {
            if (boost != this.fuelBoost) {
                // 剩余 10 分钟 +30% 续入 60 分钟 +10% → 加权平均，经济上等价交换不产生套利
                this.fuelBoost = (fuelTicks * this.fuelBoost + duration * boost) / (double) (fuelTicks + duration);
            }
            this.fuelTotalTicks += duration;
        }
        this.fuelTicks += duration;
        markDirty();
    }

    /** 添加永久燃料（取最大加速，不衰减）。 */
    public void addPermanentFuel(double boost) {
        this.permanentBoost = Math.max(this.permanentBoost, boost);
        markDirty();
    }

    /**
     * 安装产量倍率燃料（催化剂类）：仅当新倍率高于当前剩余倍率时生效并重置时长；
     * 更弱的催化剂不被消耗。返回是否实际安装。
     */
    public boolean addMultiplier(double multiplier, long durationTicks) {
        if (multiplier <= 1.0 || durationTicks <= 0) {
            return false;
        }
        if (multiplier > this.multBoost) {
            this.multBoost = multiplier;
            this.multTicks = durationTicks;
            // 催化剂为「替换」语义（不叠加）：装入量即总量，进度条分母随之确定
            this.multTotalTicks = durationTicks;
            markDirty();
            return true;
        }
        return false;
    }

    /** 当前产量倍率（1.0 = 无）。 */
    public double prodMultiplier() {
        return multBoost;
    }

    /** 产量倍率剩余 tick。 */
    public long multTicks() {
        return multTicks;
    }

    /** 本轮催化剂已装入总 tick（GUI 进度条分母；0 = 无）。 */
    public long multTotalTicks() {
        return multTotalTicks;
    }

    /** 在线处理时衰减倍率时长；归零自动复位 1.0。 */
    public void tickMultiplier(long decayTicks) {
        if (multTicks <= 0) {
            return;
        }
        multTicks -= decayTicks;
        if (multTicks <= 0) {
            multTicks = 0;
            multBoost = 1.0;
            multTotalTicks = 0; // 分母同步清空，避免残留旧会话的总量
        }
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

    /** 运行时状态（信息卡状态行/名牌/诊断结论共用）。 */
    public MinionStatus status() {
        return status;
    }

    public void setStatus(MinionStatus status) {
        this.status = status == null ? MinionStatus.WORKING : status;
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

    public MinionUpgradeType upgrade3() {
        return upgrade3;
    }

    public void setUpgrade3(MinionUpgradeType type) {
        this.upgrade3 = type;
        markDirty();
    }

    public MinionUpgradeType upgrade4() {
        return upgrade4;
    }

    public void setUpgrade4(MinionUpgradeType type) {
        this.upgrade4 = type;
        markDirty();
    }

    public boolean hasUpgrade(MinionUpgradeType type) {
        return type != null && (type == upgrade1 || type == upgrade2 || type == upgrade3 || type == upgrade4);
    }

    /** 读取指定模块槽（1~4）当前装备的模块（空槽返回 null）。 */
    public MinionUpgradeType upgradeAt(int slot) {
        return switch (slot) {
            case 1 -> upgrade1;
            case 2 -> upgrade2;
            case 3 -> upgrade3;
            default -> upgrade4;
        };
    }

    /** 装备模块到指定槽（1~4）。 */
    public void setUpgradeAt(int slot, MinionUpgradeType type) {
        switch (slot) {
            case 1 -> upgrade1 = type;
            case 2 -> upgrade2 = type;
            case 3 -> upgrade3 = type;
            default -> upgrade4 = type;
        }
        markDirty();
    }

    /** 卸下指定模块槽（1~4）的模块并返回，空槽返回 null。 */
    public MinionUpgradeType removeUpgradeSlot(int slot) {
        MinionUpgradeType removed = upgradeAt(slot);
        switch (slot) {
            case 1 -> upgrade1 = null;
            case 2 -> upgrade2 = null;
            case 3 -> upgrade3 = null;
            default -> upgrade4 = null;
        }
        if (removed != null) {
            markDirty();
        }
        return removed;
    }

    /**
     * 卸下储物箱模块后、被重新锁定的存储格里残留的物品（防止 refresh() 用锁定玻璃覆盖导致吞物）。
     * 调用方须在同 region 线程把返回物品交还玩家/掉落，然后本方法已就地清空这些槽。
     * 传入的 removed 为刚卸下的模块；非储物箱模块返回空列表。
     */
    public List<ItemStack> evictOverflowAfterRemoving(MinionUpgradeType removed) {
        if (removed == null || removed.bonusStorageSlots() <= 0) {
            return List.of();
        }
        // 模块字段此时已置空，unlockedSlots() 反映的是卸下后的新容量
        int nowUnlocked = unlockedSlots();
        List<ItemStack> evicted = new ArrayList<>();
        for (int i = nowUnlocked; i < storageSlots().length; i++) {
            ItemStack item = storage.getItem(storageSlots()[i]);
            if (item != null && item.getType() != Material.AIR) {
                evicted.add(item.clone());
                storage.setItem(storageSlots()[i], null);
            }
        }
        if (!evicted.isEmpty()) {
            markDirty();
        }
        return evicted;
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
    /** 当前是否脏（唯一真相源：仓库靠它收集待落库名单，不再维护第二份集合）。 */
    public boolean isDirty() {
        return dirty.get();
    }

    public void markClean() {
        dirty.set(false);
    }

    /** 认领脏标记（CAS）：成功返回 true 表示本线程获得本轮快照权。 */
    public boolean tryClaimFlush() {
        return dirty.compareAndSet(true, false);
    }

    public MinionData toData() {
        return new MinionData(
                id, owner, typeKey, level,
                location.world(), location.x(), location.y(), location.z(),
                fuelTicks, fuelBoost, multBoost, multTicks,
                fuelTotalTicks, multTotalTicks,
                lastActiveEpochMs, islandId,
                upgrade1 == null ? null : upgrade1.key(),
                upgrade2 == null ? null : upgrade2.key(),
                upgrade3 == null ? null : upgrade3.key(),
                upgrade4 == null ? null : upgrade4.key(),
                skin.key(),
                autoSell, totalProduced, permanentBoost,
                serializeStorageItems()
        );
    }

    public static Minion fromData(MinionData d) {
        MinionType type = MinionType.fromKey(d.type()).orElseGet(MinionType::fallback);
        Minion m = new Minion(
                d.id(), d.owner(), type, d.level(),
                new BlockLocation(d.world(), d.x(), d.y(), d.z()),
                d.fuelTicks(), d.lastActiveEpochMs(), d.islandId()
        );
        m.setFuelBoost(Math.max(1.0, d.fuelBoost()));
        // 旧存档没有总量字段：用「剩余」兜底（条显示满格），新存档按真实总量恢复
        m.fuelTotalTicks = d.fuelTicks() > 0
                ? Math.max(d.fuelTotalTicks(), d.fuelTicks())
                : 0;
        if (d.multTicks() > 0 && d.multBoost() > 1.0) {
            m.addMultiplier(d.multBoost(), d.multTicks());
            m.multTotalTicks = Math.max(d.multTotalTicks(), d.multTicks());
        }
        // 必须先装模块再灌仓库：storage 容量依赖储物箱模块（unlockedSlots），
        // 若先 setStorageItems 时模块尚未装上，超出基础容量的物品会被当溢出丢弃（数据丢失）。
        m.setUpgrade1(MinionUpgradeType.fromKey(d.upgrade1()).orElse(null));
        m.setUpgrade2(MinionUpgradeType.fromKey(d.upgrade2()).orElse(null));
        m.setUpgrade3(MinionUpgradeType.fromKey(d.upgrade3()).orElse(null));
        m.setUpgrade4(MinionUpgradeType.fromKey(d.upgrade4()).orElse(null));
        m.setStorageItems(ItemCodec.deserializeStacks(d.inventory()));
        m.setSkin(MinionSkin.fromKey(d.skin()).orElse(MinionSkin.DEFAULT));
        m.setAutoSell(d.autoSell());
        m.addProduced(d.totalProduced());
        m.setPermanentBoost(Math.max(1.0, d.permanentBoost()));
        m.markClean();
        return m;
    }

    /**
     * 仓库容器 holder：携带所属仆从 id，并在创建后回填真实 {@link Inventory}，
     * 满足 {@link InventoryHolder} 契约（第三方插件调 {@code holder.getInventory()} 不再 NPE）。
     */
    public static final class StorageHolder implements InventoryHolder {
        private final UUID minionId;
        private Inventory inventory;

        public StorageHolder(UUID minionId) {
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
}
