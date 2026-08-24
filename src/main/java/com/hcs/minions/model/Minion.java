package com.hcs.minions.model;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.upgrade.MinionUpgradeType;
import com.hcs.minions.util.GuiLayout;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.ItemCodec;
import com.hcs.minions.util.ItemRef;
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

    public static final int GUI_SIZE = 54;

    // Hypixel 风格布局（槽位/材质均由 gui.yml layout 段驱动，见 {@link GuiLayout}）：
    //   顶行       : 燃料 | 装饰 | 装饰 | 信息卡 | 头颅(居中) | 升级 | 装饰 | 皮肤 | 装饰
    //   存储区     : 36 格（按 Tier 解锁，锁定格用 locked 材质）
    //   底行       : 装饰 | 装饰 | 模块1 | 模块2 | 收集全部(居中) | 自动售卖 | 理想布局 | 拾取 | 关闭
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

    public static int collectSlot() {
        return GuiLayout.slot("storage.collect.slot");
    }

    public static int autosellSlot() {
        return GuiLayout.slot("storage.autosell.slot");
    }

    public static int layoutSlot() {
        return GuiLayout.slot("storage.layout.slot");
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
    private volatile double fuelBoost = 1.0;
    /** 产量倍率燃料（催化剂类）：值与剩余时长，仅在线处理时衰减。 */
    private volatile double multBoost = 1.0;
    private volatile long multTicks;
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
    /** 布局摆放的方块位置 -> 摆放前的原方块（还原依据）。 */
    private final java.util.Map<BlockLocation, Material> layoutBlocks = new java.util.concurrent.ConcurrentHashMap<>();
    /** 自动布局可能播种的作物集合（农夫布局还原时识别「我们种的作物」）。 */
    private static final java.util.Set<Material> LAYOUT_CROPS =
            java.util.EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS);

    private final AtomicLong nextWorkTick = new AtomicLong();
    private volatile int scanCursor;
    private volatile ArmorStand stand;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /**
     * 落库通知钩子：markDirty() 时同步把本仆从 id 登记进仓库脏集合，
     * 保证「运行期产出/燃料衰减」与「GUI 操作」走同一条持久化链路。
     * 由 {@code CachedMinionRepository#register} 注入；未注册时仅置内存标记（如单测）。
     */
    private volatile Runnable dirtyHook;
    /** 上次为观看中的 GUI 刷新状态卡的时间（每秒一次，节流用）。 */
    private volatile long lastGuiRefreshTick;

    /** 注册落库钩子（仓库缓存层在仆从入缓存时调用）。 */
    public void setDirtyHook(Runnable hook) {
        this.dirtyHook = hook;
    }

    public void markDirty() {
        dirty.set(true);
        Runnable hook = dirtyHook;
        if (hook != null) {
            hook.run();
        }
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
        this.storage = Bukkit.createInventory(new StorageHolder(id), GUI_SIZE,
                GuiText.title("title", Map.of("name", type().displayName())));
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

    // ---- 理想布局 ----
    public boolean idealLayout() {
        return idealLayout;
    }

    public void setIdealLayout(boolean on) {
        this.idealLayout = on;
    }

    /** 记录布局摆放的方块位置 -> 摆放前的原方块（关闭/拾取时按原样还原）。 */
    public void addLayoutBlock(BlockLocation loc, Material original) {
        layoutBlocks.putIfAbsent(loc, original);
    }

    public boolean hasLayoutBlocks() {
        return !layoutBlocks.isEmpty();
    }

    /**
     * 还原布局摆放的方块（尽力而为）：只清理「我们摆上去的产物」——
     * 原为空气的位置上的流体/作物清为空气，被耕过的耕地还原为原方块；
     * 玩家后续改动过的位置不触碰。关闭理想布局与拾取仆从时调用。
     */
    public void cleanupLayoutBlocks() {
        if (layoutBlocks.isEmpty()) {
            return;
        }
        World world = location.bukkitWorld();
        if (world != null) {
            for (Map.Entry<BlockLocation, Material> e : layoutBlocks.entrySet()) {
                Block b = world.getBlockAt(e.getKey().x(), e.getKey().y(), e.getKey().z());
                Material orig = e.getValue();
                if (orig.isAir()) {
                    if (b.getType() == Material.WATER || b.getType() == Material.LAVA
                            || LAYOUT_CROPS.contains(b.getType())) {
                        b.setType(Material.AIR, false);
                    }
                } else if (b.getType() == Material.FARMLAND && orig != Material.FARMLAND) {
                    b.setType(orig, false); // 耕地还原为泥土/草方块等
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
        return Math.min(storageSlots().length, 9 + (level - 1) * 3);
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
        // 统一深色边框玻璃（顶栏/底栏同色，存储区锁定格用浅色区分）——材质均由 layout 配置
        ItemStack decor = named(GuiLayout.material("storage.decor.material"), Component.empty());
        for (int s : GuiLayout.slots("storage.decor.slots")) {
            storage.setItem(s, decor);
        }
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
        storage.setItem(module1Slot(), upgradeSlotItem(upgrade1, 1, unlockedUpgradeSlots() >= 1));
        storage.setItem(module2Slot(), upgradeSlotItem(upgrade2, 2, unlockedUpgradeSlots() >= 2));
        storage.setItem(collectSlot(), collectItem());
        storage.setItem(autosellSlot(), autoSellItem());
        storage.setItem(layoutSlot(), layoutItem(cfg));
        storage.setItem(pickupSlot(), pickupItem());
        storage.setItem(closeSlot(), named(GuiLayout.material("storage.close.material"), GuiText.title("close.title")));
    }

    /** 信息卡（Hypixel 信息书风格）：文案来自 gui.yml（info.*），数据以占位符注入；
     *  未配置稀有掉落时稀有行自动隐藏（可选行机制）。 */
    private ItemStack infoItem(MinionTypeConfig cfg) {
        double secondsPerAction = secondsPerAction(cfg, level);
        int side = 2 * cfg.radiusFor(level) + 1;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", cfg.displayName());
        v.put("tier", Roman.of(level));
        v.put("speed", String.format("%.1f", secondsPerAction));
        v.put("rate", String.valueOf(itemsPerHour(secondsPerAction, cfg.harvestCap())));
        v.put("range", side + "x" + side);
        v.put("storage", String.valueOf(storageCount()));
        v.put("slots", String.valueOf(unlockedSlots()));
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
        if (isStorageFull()) {
            v.put("status", "<red>⚠ 仓库已满 · 停工中</red>");
            v.put("halted_tip", ""); // 可选行：停工时追加恢复提示
        } else {
            v.put("status", "<green>● 工作中</green>");
        }
        v.put("total", String.valueOf(totalProduced));
        v.put("next", String.valueOf(nextWorkSeconds()));
        return named(GuiLayout.material("storage.info.material"), GuiText.title("info.title", v), GuiText.lore("info.lore", v));
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
    long storageCount() {
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
        String ownerName = Bukkit.getOfflinePlayer(owner).getName();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", cfg.displayName());
        v.put("tier", Roman.of(level));
        v.put("owner", ownerName == null ? "?" : ownerName);
        v.put("skin", skin.displayName());
        return named(type().icon(), GuiText.title("head.title", v), GuiText.lore("head.lore", v));
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
        if (multBoost > 1.0) {
            v.put("mult", "×" + (multBoost == Math.floor(multBoost)
                    ? String.valueOf((long) multBoost) : String.valueOf(multBoost)));
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
            v.put("body", "<dark_gray>· <aqua>仆从本体</aqua> <white>×1</white> <gray>（同类型当前等级）</gray>");
        }
        int row = 1;
        for (Map.Entry<ItemRef, Long> e : recipe.entrySet()) {
            if (row > 4) {
                break; // GUI 最多展示 4 行材料
            }
            long owned = countInStorage(e.getKey());
            if (owned < e.getValue()) {
                enough = false;
            }
            v.put("m" + row, recipeLine(e.getKey(), e.getValue(), owned));
            row++;
        }
        String key = enough ? "upgrade" : "upgrade-lack";
        return named(GuiLayout.material("storage.upgrade.material-" + (enough ? "ok" : "lack")),
                GuiText.title(key + ".title", v), GuiText.lore(key + ".lore", v));
    }

    /** 单行材料对比（MiniMessage 片段）：材料名颜色随足够与否变化，悬浮显示获取指引。 */
    private static String recipeLine(ItemRef ref, long need, long owned) {
        String nameColor = owned >= need ? "<green>" : "<red>";
        String inner = "<dark_gray>· " + nameColor + ref.displayName() + " <white>×" + need + "</white>"
                + " <gray>已有 " + owned;
        return MaterialGuide.wrapHover(ref.guideMaterial(), inner);
    }

    private ItemStack upgradeSlotItem(MinionUpgradeType upgrade, int n, boolean unlocked) {
        if (!unlocked) {
            int tier = n == 1 ? UPGRADE_SLOT1_UNLOCK_TIER : UPGRADE_SLOT2_UNLOCK_TIER;
            Map<String, String> v = Map.of("n", String.valueOf(n), "tier", Roman.of(tier));
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

    private ItemStack autoSellItem() {
        String key = autoSell ? "autosell-on" : "autosell-off";
        Material icon = GuiLayout.material("storage.autosell.material-" + (autoSell ? "on" : "off"));
        return named(icon, GuiText.title(key + ".title"), GuiText.lore(key + ".lore"));
    }

    private ItemStack skinItem() {
        MinionSkin[] skins = MinionSkin.values();
        MinionSkin next = skins[(skin.ordinal() + 1) % skins.length];
        Map<String, String> v = new LinkedHashMap<>();
        v.put("current", skin.displayName());
        v.put("next", next.displayName());
        return named(GuiLayout.material("storage.skin.material"), GuiText.title("skin.title", v), GuiText.lore("skin.lore", v));
    }

    private ItemStack layoutItem(MinionTypeConfig cfg) {
        int side = 2 * cfg.radiusFor(level) + 1;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("side", String.valueOf(side));
        switch (type().behavior()) {
            // 可执行开关类型：可选行 on/off 只渲染当前状态行
            case GENERATOR -> {
                v.put(idealLayout ? "on" : "off", "");
                v.put("layout_hint", "自动摆水与岩浆，搭建经典刷石机");
            }
            case FARMING -> {
                v.put(idealLayout ? "on" : "off", "");
                v.put("layout_hint", "自动耕地播种，作物成熟后自动收割");
            }
            case MINING -> v.put("layout_hint", "矿石不会再生：请在范围内手动摆放目标矿石");
            case FORAGING -> v.put("layout_hint", "请在范围内种树（砍伐后自动补种树苗）");
            case FISHING -> v.put("layout_hint", "请在范围内留出水面");
            default -> v.put("layout_hint", "该类型自动产出，无需布置方块");
        }
        return named(GuiLayout.material("storage.layout.material"), GuiText.title("layout.title", v), GuiText.lore("layout.lore", v));
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

    /** 添加限时燃料。续期时按剩余时长加权平均加速，避免低级燃料白蹭高级加成。 */
    public void addFuel(long duration, double boost) {
        if (duration <= 0) {
            return;
        }
        if (fuelTicks <= 0) {
            this.fuelBoost = boost;
        } else if (boost != this.fuelBoost) {
            // 剩余 10 分钟 +30% 续入 60 分钟 +10% → 加权平均，经济上等价交换不产生套利
            this.fuelBoost = (fuelTicks * this.fuelBoost + duration * boost) / (double) (fuelTicks + duration);
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

    /** 在线处理时衰减倍率时长；归零自动复位 1.0。 */
    public void tickMultiplier(long decayTicks) {
        if (multTicks <= 0) {
            return;
        }
        multTicks -= decayTicks;
        if (multTicks <= 0) {
            multTicks = 0;
            multBoost = 1.0;
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
                fuelTicks, fuelBoost, multBoost, multTicks, lastActiveEpochMs, islandId,
                upgrade1 == null ? null : upgrade1.key(),
                upgrade2 == null ? null : upgrade2.key(),
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
        if (d.multTicks() > 0 && d.multBoost() > 1.0) {
            m.addMultiplier(d.multBoost(), d.multTicks());
        }
        m.setStorageItems(ItemCodec.deserializeStacks(d.inventory()));
        m.setUpgrade1(MinionUpgradeType.fromKey(d.upgrade1()).orElse(null));
        m.setUpgrade2(MinionUpgradeType.fromKey(d.upgrade2()).orElse(null));
        m.setSkin(MinionSkin.fromKey(d.skin()).orElse(MinionSkin.DEFAULT));
        m.setAutoSell(d.autoSell());
        m.addProduced(d.totalProduced());
        m.setPermanentBoost(Math.max(1.0, d.permanentBoost()));
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
