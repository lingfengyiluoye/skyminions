package com.hcs.minions.gui;

import com.hcs.minions.model.Minion;
import com.hcs.minions.config.FuelEntry;
import com.hcs.minions.service.FuelService;
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
 * 燃料选择 GUI（对齐文档：点燃料槽 → 打开选择界面，从背包装燃料）。
 *
 * <pre>
 * 27 格：10..16 列出背包中拥有的燃料（图标 + 加速% + 持续时间 + 背包数量）
 *        22 当前燃料状态卡 | 26 关闭
 * 交互：左键安装该燃料全部库存（限时按数量累计时长/永久仅 1 个），潜行左键仅安装 1 个。
 * </pre>
 */
public final class FuelGui {

    /** 燃料选择界面容器：携带所属仆从 id 与「槽位 -> 燃料条目」映射
     *  （附魔资源催化剂与同材质散装物品只能靠条目身份区分，不能按图标反查）。 */
    public static final class FuelHolder implements InventoryHolder {
        private final UUID minionId;
        private final Map<Integer, FuelEntry> options;
        private Inventory inventory;

        public FuelHolder(UUID minionId, Map<Integer, FuelEntry> options) {
            this.minionId = minionId;
            this.options = options;
        }

        public UUID minionId() {
            return minionId;
        }

        /** 槽位对应的燃料条目（非选项槽返回 null）。 */
        public FuelEntry optionAt(int slot) {
            return options.get(slot);
        }

        void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** 燃料选项槽（中间行，gui.yml layout.fuel-gui.option.slots 可配）。 */
    public static int[] optionSlots() {
        return GuiLayout.slots("fuel-gui.option.slots");
    }

    public static int statusSlot() {
        return GuiLayout.slot("fuel-gui.status.slot");
    }

    public static int closeSlot() {
        return GuiLayout.slot("fuel-gui.close.slot");
    }

    private FuelGui() {
    }

    /** 打开燃料选择界面：只列出玩家背包中实际拥有的燃料（含附魔资源催化剂）。 */
    public static void open(Player player, Minion minion) {
        // 先算出「拥有的燃料 -> 槽位」映射，再建界面（holder 需要它）
        int[] optionSlots = optionSlots();
        int status = statusSlot();
        int close = closeSlot();
        Map<Integer, FuelEntry> slotOptions = new LinkedHashMap<>();
        List<FuelEntry> ownedEntries = new ArrayList<>();
        int slotIndex = 0;
        for (FuelEntry entry : FuelService.all()) {
            if (FuelService.countOwned(player, entry) <= 0 || slotIndex >= optionSlots.length) {
                continue;
            }
            int slot = optionSlots[slotIndex];
            // 旧配置把状态卡放在选项区中间（如 slot 22）时，跳过该槽，
            // 避免状态卡覆盖选项导致那种燃料在 GUI 中点不到
            if (slot == status || slot == close) {
                slotIndex++;
                continue;
            }
            slotOptions.put(slot, entry);
            ownedEntries.add(entry);
            slotIndex++;
        }

        Inventory inv = Bukkit.createInventory(
                new FuelHolder(minion.id(), Map.copyOf(slotOptions)), 27,
                GuiText.title("fuel-gui.title"));
        if (inv.getHolder() instanceof FuelHolder holder) {
            holder.attach(inv);
        }
        for (Map.Entry<Integer, FuelEntry> e : slotOptions.entrySet()) {
            FuelEntry entry = e.getValue();
            inv.setItem(e.getKey(), optionItem(entry, FuelService.countOwned(player, entry)));
        }
        if (slotOptions.isEmpty() && optionSlots.length > 0) {
            inv.setItem(optionSlots[optionSlots.length / 2], named(GuiLayout.material("fuel-gui.empty.material"),
                    GuiText.title("fuel-gui.empty.title")));
        }
        inv.setItem(statusSlot(), statusItem(minion));
        inv.setItem(closeSlot(), named(GuiLayout.material("fuel-gui.close.material"),
                GuiText.title("fuel-gui.close.title")));
        player.openInventory(inv);
    }

    /** 燃料选项卡：物品 + 加速% + 产量倍率 + 持续时间 + 背包数量。 */
    private static ItemStack optionItem(FuelEntry fv, int count) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", fv.displayName() == null ? MaterialNames.of(fv.icon()) : fv.displayName());
        v.put("boost", String.valueOf((int) ((fv.boost() - 1) * 100)));
        v.put("mult", fv.hasMultiplier() ? "×" + trimDouble(fv.multiplier()) : "-");
        v.put("duration", fv.permanent() ? "永久" : fmtDuration(fv.durationTicks()));
        v.put("count", String.valueOf(count));
        ItemStack item = new ItemStack(fv.icon());
        ItemMeta meta = item.getItemMeta();
        Component title = GuiText.title("fuel-gui.option.title", v)
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(title);
        List<Component> lore = GuiText.lore("fuel-gui.option.lore", v);
        meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    /** 当前燃料状态卡（剩余时间/永久加速/无燃料）。 */
    public static ItemStack statusItem(Minion minion) {
        Map<String, String> v = new LinkedHashMap<>();
        if (minion.permanentBoost() > 1.0) {
            v.put("perm", String.valueOf((int) ((minion.permanentBoost() - 1) * 100)));
        }
        if (minion.fuelTicks() > 0) {
            v.put("left", fmtDuration(minion.fuelTicks()));
        }
        if (minion.permanentBoost() <= 1.0 && minion.fuelTicks() <= 0) {
            v.put("none", "");
        }
        Material icon = minion.permanentBoost() > 1.0 ? Material.LAVA_BUCKET
                : minion.fuelTicks() > 0 ? Material.BLAZE_ROD : Material.COAL;
        return named(icon, GuiText.title("fuel-gui.status.title", v),
                GuiText.lore("fuel-gui.status.lore", v));
    }

    private static String fmtDuration(long ticks) {
        long seconds = ticks / 20;
        if (seconds >= 3600) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            return m > 0 ? h + " 小时 " + m + " 分钟" : h + " 小时";
        }
        if (seconds >= 60) {
            return (seconds / 60) + " 分钟";
        }
        return seconds + " 秒";
    }

    /** 倍率展示：整数不带小数点，其余保留一位。 */
    private static String trimDouble(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
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
}
