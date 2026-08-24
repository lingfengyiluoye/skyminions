package com.hcs.minions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 玩家可见文案：可配置（messages.yml）+ 默认值回退 + 热重载。
 *
 * <p>启动时 {@link #load(JavaPlugin)} 从 messages.yml 读取（MiniMessage 格式，
 * 占位符 {0} {1} ...），缺失的键回退到内置默认文案。/minion reload 会再次调用
 * load 实现文案热更新。</p>
 *
 * <p>无参消息以静态字段暴露（业务代码 {@code Messages.NO_PERMISSION} 等），
 * 带参消息为静态方法（{@code Messages.fuelAdded(pct)} 等），签名与早期版本
 * 完全一致，业务代码零改动。</p>
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** 内置默认文案（messages.yml 缺失时回退），键与 messages.yml 一致。 */
    private static final Map<String, String> DEFAULTS = new HashMap<>();

    /** 从 messages.yml 加载的文案（键 -> MiniMessage 模板）。 */
    private static volatile Map<String, String> templates = Map.of();

    private Messages() {
    }

    // ------------------------------------------------------------------
    // 无参消息字段（非 final，load 时重新赋值，支持热重载）
    // ------------------------------------------------------------------

    public static Component NO_PERMISSION;
    public static Component MUST_PLACE_ON_ISLAND;
    public static Component LIMIT_REACHED;
    public static Component NOT_YOUR_MINION;
    public static Component PLAYER_ONLY;
    public static Component UNKNOWN_COMMAND;
    public static Component MAX_LEVEL;
    public static Component STORAGE_EMPTY;
    public static Component COLLECTED_ALL;
    public static Component UPGRADE_SLOT_LOCKED;
    public static Component UPGRADE_SLOT_OCCUPIED;
    public static Component UPGRADE_SLOT_EMPTY;
    public static Component USAGE;
    public static Component USAGE_GIVE;
    public static Component USAGE_UPGRADE;
    public static Component LEVEL_MUST_BE_NUMBER;
    public static Component SKIN_TIP;
    public static Component LAYOUT_HEADER;
    public static Component LAYOUT_TIP_CENTER;
    public static Component LAYOUT_TIP_LIGHT;
    public static Component LAYOUT_TIP_SHARED;
    public static Component COLLECTION_EMPTY;
    public static Component COLLECTION_HEADER;
    public static Component COLLECTION_COMPLETE;
    public static Component FUEL_HELP_HEADER;
    public static Component FUEL_HELP_TIP;
    public static Component FUEL_STATUS_NONE;

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    static {
        defaults();
        assignStatics();
    }

    private static void defaults() {
        DEFAULTS.put("no-permission", "<red>✖ 你没有权限使用该仆从</red>");
        DEFAULTS.put("must-place-on-island", "<red>✖ 仆从只能放置在 <gold>你的空岛</gold> 内</red>");
        DEFAULTS.put("limit-reached", "<red>✖ 仆从数量已达上限</red>\n<gray>升级权限 <gold>hcs.minions.limit.\\<数量></gold> 可增加上限</gray>");
        DEFAULTS.put("not-your-minion", "<red>✖ 无法操作该仆从</red>\n<gray>仅限仆从主人或所在空岛的团队成员使用</gray>");
        DEFAULTS.put("player-only", "<red>✖ 该命令仅玩家可执行</red>");
        DEFAULTS.put("unknown-command", "<red>✖ 未知子命令，输入 <gold>/minion</gold> 查看帮助</red>");
        DEFAULTS.put("permanent-fuel-equipped", "<green>✔ 已装备<bold>永久燃料</bold></green>\n<gray>持续加速 <yellow>+{0}%</yellow>，不随时间衰减</gray>");
        DEFAULTS.put("fuel-added", "<green>✔ 已补充燃料</green>\n<gray>加速 <yellow>+{0}%</yellow>，燃料已加入储备</gray>");
        DEFAULTS.put("fuel-help-header", "<gold><bold>▼ 燃料</bold></gold>");
        DEFAULTS.put("fuel-status-permanent", "<gray>当前: <green>永久加速 +{0}%</green>（不衰减）</gray>");
        DEFAULTS.put("fuel-status-timed", "<gray>当前: <yellow>限时加速中，剩余 {0} 秒</yellow></gray>");
        DEFAULTS.put("fuel-status-none", "<gray>当前: <red>无燃料</red>（仆从仍会工作，燃料只提供加速）</gray>");
        DEFAULTS.put("fuel-help-tip", "<yellow>⚡ 手持燃料点击左上角燃料槽即可添加，立即生效</yellow>\n<dark_gray>限时燃料整组生效；也可手持燃料直接右键小人</dark_gray>");
        DEFAULTS.put("fuel-help-line", "<dark_gray>· <white>{0}</white> <green>+{1}%</green> <gray>({2})</gray></dark_gray>");
        DEFAULTS.put("fuel-unequipped", "<green>✔ 已卸下限时燃料</green>");
        DEFAULTS.put("fuel-unequip-empty", "<gray>当前没有限时燃料可卸下</gray>");
        DEFAULTS.put("max-level", "<red>✖ 仆从已满级，无法继续升级</red>");
        DEFAULTS.put("storage-empty", "<gray>仓库是空的，仆从还没有收获</gray>");
        DEFAULTS.put("collected-all", "<green>✔ 已取出全部产物</green>");
        DEFAULTS.put("upgrade-slot-locked", "<red>✖ 该模块槽尚未解锁</red>\n<gray>提升到对应 <gold>等级</gold> 后可解锁</gray>");
        DEFAULTS.put("upgrade-slot-occupied", "<red>✖ 该模块槽已被占用</red>\n<gray>先卸下当前模块再装备新的</gray>");
        DEFAULTS.put("upgrade-slot-empty", "<gray>该模块槽是空的</gray>");
        DEFAULTS.put("auto-sell-toggled", "<green>✔ 自动出售已{0}</green>");
        DEFAULTS.put("upgrade-equipped", "<green>✔ 已装备模块</green>\n<aqua>{0}</aqua>");
        DEFAULTS.put("upgrade-removed", "<green>✔ 已卸下模块</green>\n<aqua>{0}</aqua> 已返还到背包");
        DEFAULTS.put("skin-changed", "<light_purple>✔ 皮肤已切换</light_purple>\n<light_purple>{0}</light_purple>");
        DEFAULTS.put("upgrade-failed", "<red>✖ 升级失败</red>\n<gray>还差 <gold>{0}</gold>，把材料放进仆从仓库后再试</gray>");
        DEFAULTS.put("upgrade-missing-body", "<red>✖ 升级失败</red>\n<gray>还需 <gold>1 个 {0} 等级 {1}</gold> 仆从本体（放进仆从仓库或背包）</gray>");
        DEFAULTS.put("upgrade-success", "<green>✔ 升级成功！</green>\n<gray>当前 <gold>等级 {0}</gold></gray>");
        DEFAULTS.put("usage", "<gray>SkyMinions 管理命令</gray>\n<white>/minion give \\<类型> [等级]</white> <gray>- 发放仆从</gray>\n<white>/minion upgrade \\<模块></white> <gray>- 发放模块</gray>\n<white>/minion skin</white> <gray>- 查看皮肤</gray>\n<white>/minion collection</white> <gray>- 资源累计</gray>\n<white>/minion reload</white> <gray>- 重载配置</gray>\n<white>/minion purge</white> <gray>- 清理残留</gray>\n<white>/minion list</white> <gray>- 在线仆从数</gray>");
        DEFAULTS.put("usage-give", "<red>用法</red><gray>: /minion give \\<类型> [等级]</gray>");
        DEFAULTS.put("usage-upgrade", "<red>用法</red><gray>: /minion upgrade \\<模块></gray>\n<dark_gray>可选: auto_smelter(自动熔炼) | compactor(自动压缩) | super_compactor(超级压缩) | diamond_spreading(钻石散布) | minion_expander(范围扩展) | auto_seller(自动售卖)</dark_gray>");
        DEFAULTS.put("level-must-be-number", "<red>✖ 等级必须是数字</red>");
        DEFAULTS.put("unknown-type", "<red>✖ 未知类型: {0}</red>");
        DEFAULTS.put("unknown-upgrade", "<red>✖ 未知模块: {0}</red>");
        DEFAULTS.put("given-minion", "<green>✔ 已发放</green> <gold>{0}</gold> <white>等级 {1}</white>\n<gray>右键方块放置仆从</gray>");
        DEFAULTS.put("given-upgrade", "<green>✔ 已发放模块</green> <aqua>{0}</aqua>\n<gray>手持模块点击仆从的模块槽装备</gray>");
        DEFAULTS.put("total-minions", "<yellow>当前在线仆从总数: {0}</yellow>");
        DEFAULTS.put("config-reloaded", "<green>✔ 配置与文案已重新加载</green>");
        DEFAULTS.put("purged", "<green>✔ 已清理 {0} 个残留仆从实体</green>");
        DEFAULTS.put("available-skins", "<gray>可用皮肤</gray><white>: {0}</white>");
        DEFAULTS.put("skin-tip", "<gray>在仆从 GUI 中点击 <light_purple>皮肤</light_purple> 按钮切换</gray>");
        DEFAULTS.put("collection-empty", "<gray>暂无资源累计记录</gray>\n<dark_gray>仆从产出后会自动记录</dark_gray>");
        DEFAULTS.put("collection-header", "<aqua><bold>▼ 资源累计</bold></aqua>");
        DEFAULTS.put("collection-entry", "<white>{0}</white> <gray>x</gray> <yellow>{1}</yellow>");
        DEFAULTS.put("collection-next", "<dark_gray>→ 下一里程碑: {0} / {1}</dark_gray>");
        DEFAULTS.put("collection-complete", "<green>✓ 全部里程碑已达成</green>");
        DEFAULTS.put("collection-slot-bonus", "<green>✔ 里程碑加成: 仆从槽位 +{0}</green>");
        DEFAULTS.put("milestone-reached", "<green>✔ 资源累计里程碑达成！</green>\n<white>{0}</white> <gray>累计</gray> <yellow>{1}</yellow>\n<gray>奖励 <gold>{2} 金币</gold></gray>");
        DEFAULTS.put("milestone-slot-bonus", "<gray>，仆从槽位 <yellow>+1</yellow></gray>");
        DEFAULTS.put("rare-drop", "<gold>✦ 你的{0}获得了稀有掉落</gold> <light_purple>{1}</light_purple>");
        DEFAULTS.put("layout-header", "<aqua><bold>▼ 理想布局</bold></aqua>");
        DEFAULTS.put("layout-tip-center", "<gray>· 中心留空，四周填充可采集方块</gray>");
        DEFAULTS.put("layout-tip-light", "<gray>· 保证光照 ≥ 8，防止刷怪干扰</gray>");
        DEFAULTS.put("layout-tip-shared", "<gray>· 多个仆从共享边界，可最大化产出</gray>");
        DEFAULTS.put("layout-range", "<white>工作范围</white><gray>: <yellow>{0}x{0}</yellow>（中心放置仆从）</gray>");
        DEFAULTS.put("layout-enabled", "<green>✔ 理想布局已开启</green>\n<gray>仆从将自动摆放水与岩浆搭建刷石机</gray>");
        DEFAULTS.put("layout-disabled", "<gray>理想布局已关闭，摆放的水与岩浆已还原</gray>");
        DEFAULTS.put("unlock-required", "<red>✖ 该仆从类型尚未解锁</red>\n<gray>需累计收集 <white>{0}</white> 达到 <yellow>{1}</yellow>（当前 {2}）</gray>");
        DEFAULTS.put("offline-reward", "<green>✔ 离线收益已到账</green>\n<gray>仆从为你产出了 <yellow>{0}</yellow> 件物品</gray>\n<gray>（离线约 <white>{1}</white> 分钟）</gray>");
    }

    /** 从 messages.yml 加载文案；文件不存在则写出默认文件。可重复调用（热重载）。 */
    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, String> loaded = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) {
                loaded.put(key, yaml.getString(key));
            }
        }
        templates = Map.copyOf(loaded);
        assignStatics();
        Logs.info("文案已加载（{} 条，来自 messages.yml）", loaded.size());
    }

    private static void assignStatics() {
        NO_PERMISSION = render("no-permission");
        MUST_PLACE_ON_ISLAND = render("must-place-on-island");
        LIMIT_REACHED = render("limit-reached");
        NOT_YOUR_MINION = render("not-your-minion");
        PLAYER_ONLY = render("player-only");
        UNKNOWN_COMMAND = render("unknown-command");
        MAX_LEVEL = render("max-level");
        STORAGE_EMPTY = render("storage-empty");
        COLLECTED_ALL = render("collected-all");
        UPGRADE_SLOT_LOCKED = render("upgrade-slot-locked");
        UPGRADE_SLOT_OCCUPIED = render("upgrade-slot-occupied");
        UPGRADE_SLOT_EMPTY = render("upgrade-slot-empty");
        USAGE = render("usage");
        USAGE_GIVE = render("usage-give");
        USAGE_UPGRADE = render("usage-upgrade");
        LEVEL_MUST_BE_NUMBER = render("level-must-be-number");
        SKIN_TIP = render("skin-tip");
        LAYOUT_HEADER = render("layout-header");
        LAYOUT_TIP_CENTER = render("layout-tip-center");
        LAYOUT_TIP_LIGHT = render("layout-tip-light");
        LAYOUT_TIP_SHARED = render("layout-tip-shared");
        COLLECTION_EMPTY = render("collection-empty");
        COLLECTION_HEADER = render("collection-header");
        COLLECTION_COMPLETE = render("collection-complete");
        FUEL_HELP_HEADER = render("fuel-help-header");
        FUEL_HELP_TIP = render("fuel-help-tip");
        FUEL_STATUS_NONE = render("fuel-status-none");
    }

    // ------------------------------------------------------------------
    // 带参消息方法（签名与早期版本一致，业务代码零改动）
    // ------------------------------------------------------------------

    public static Component permanentFuelEquipped(int pct) {
        return render("permanent-fuel-equipped", pct);
    }

    public static Component fuelAdded(int pct) {
        return render("fuel-added", pct);
    }

    public static Component fuelStatusPermanent(int pct) {
        return render("fuel-status-permanent", pct);
    }

    public static Component fuelStatusTimed(long seconds) {
        return render("fuel-status-timed", seconds);
    }

    public static Component fuelHelpLine(String name, String pct, String duration) {
        return render("fuel-help-line", name, pct, duration);
    }

    public static Component fuelUnequipped() {
        return render("fuel-unequipped");
    }

    public static Component fuelUnequipEmpty() {
        return render("fuel-unequip-empty");
    }

    public static Component autoSellToggled(boolean on) {
        return render("auto-sell-toggled", on ? "开启" : "关闭");
    }

    public static Component upgradeEquipped(String name) {
        return render("upgrade-equipped", name);
    }

    public static Component upgradeRemoved(String name) {
        return render("upgrade-removed", name);
    }

    public static Component skinChanged(String name) {
        return render("skin-changed", name);
    }

    public static Component upgradeFailed(String missing) {
        return render("upgrade-failed", missing);
    }

    public static Component upgradeMissingBody(String name, int level) {
        return render("upgrade-missing-body", name, level);
    }

    public static Component upgradeSuccess(int tier) {
        return render("upgrade-success", tier);
    }

    public static Component unknownType(String type) {
        return render("unknown-type", type);
    }

    public static Component unknownUpgrade(String type) {
        return render("unknown-upgrade", type);
    }

    public static Component givenMinion(String type, int level) {
        return render("given-minion", type, level);
    }

    public static Component givenUpgrade(String name) {
        return render("given-upgrade", name);
    }

    public static Component totalMinions(int total) {
        return render("total-minions", total);
    }

    public static Component configReloaded() {
        return render("config-reloaded");
    }

    public static Component purged(int count) {
        return render("purged", count);
    }

    public static Component availableSkins(String list) {
        return render("available-skins", list);
    }

    public static Component layoutRange(int side) {
        return render("layout-range", side);
    }

    public static Component layoutEnabled() {
        return render("layout-enabled");
    }

    public static Component layoutDisabled() {
        return render("layout-disabled");
    }

    public static Component unlockRequired(String product, long need, long have) {
        return render("unlock-required", product, need, have);
    }

    public static Component collectionEntry(String key, long value) {
        return render("collection-entry", key, value);
    }

    public static Component collectionNext(long current, long next) {
        return render("collection-next", current, next);
    }

    public static Component collectionSlotBonus(int slots) {
        return render("collection-slot-bonus", slots);
    }

    /** 里程碑达成消息（{3} 为槽位加成尾注，单独渲染避免嵌套解析）。 */
    public static Component milestoneReached(String material, long threshold, long coins, boolean slotBonus) {
        Component base = render("milestone-reached", material, threshold, coins);
        return slotBonus ? base.append(render("milestone-slot-bonus")) : base;
    }

    public static Component rareDrop(String minionName, String itemName) {
        return render("rare-drop", minionName, itemName);
    }

    public static Component offlineReward(long amount, long minutes) {
        return render("offline-reward", amount, minutes);
    }

    // ------------------------------------------------------------------
    // 渲染核心
    // ------------------------------------------------------------------

    /**
     * 渲染指定键的文案：从 templates 取，缺失回退 DEFAULTS，
     * 将 {n} 占位符替换为参数并解析 MiniMessage。
     */
    private static Component render(String key, Object... args) {
        String tpl = templates.getOrDefault(key, DEFAULTS.getOrDefault(key, ""));
        if (tpl == null || tpl.isEmpty()) {
            return Component.text(key, NamedTextColor.WHITE);
        }
        // 支持 messages.yml 中的 \n 多行文案（转成 MiniMessage 换行标签）
        String replaced = tpl.replace("\n", "<newline>");
        TagResolver.Builder builder = TagResolver.builder();
        for (int i = 0; i < args.length; i++) {
            String tag = "arg" + i;
            replaced = replaced.replace("{" + i + "}", "<" + tag + ">");
            builder.resolver(TagResolver.resolver(tag,
                    Tag.inserting(Component.text(String.valueOf(args[i])))));
        }
        try {
            return MM.deserialize(replaced, builder.build());
        } catch (Exception e) {
            Logs.warn("文案渲染失败 key={} tpl={}", key, tpl);
            return Component.text(tpl, NamedTextColor.WHITE);
        }
    }
}
