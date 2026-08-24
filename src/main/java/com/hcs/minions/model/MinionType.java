package com.hcs.minions.model;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 仆从类型（身份层，配置驱动）。
 * <p>由 config.yml 的 {@code types:} 段在加载时注册（{@link #loadAll}），
 * 每种资源一个类型，行为复用 {@link MinionBehavior} 七原型。</p>
 *
 * <p>并发模型：注册表整体 volatile 替换（与 GuiText 同款），热重载安全；
 * 实例为规范化的不可变对象，{@code fromKey} 返回同一实例，
 * 可安全用 == 比较升级本体匹配等场景。</p>
 */
public final class MinionType {

    private static final MinionType FALLBACK =
            new MinionType("unknown", "未知仆从", MinionBehavior.MINING, MinionCategory.MINING, Material.STONE);

    private static volatile Map<String, MinionType> registry = Map.of();
    /** 保序快照（图鉴/Tab 补全展示顺序 = 配置声明顺序）。 */
    private static volatile List<MinionType> ordered = List.of(FALLBACK);

    /**
     * 历史 key 别名总表：数据库存量行与玩家生成物 PDC 可能携带下列任一旧标识，
     * fromKey 未命中注册表时经此映射到最终中文名 —— 存档零迁移。
     * （覆盖两代旧标识：最初英文 key / 中文化第一版过渡 key）
     */
    private static final Map<String, String> LEGACY_ALIASES = Map.ofEntries(
            // 最初英文 key
            Map.entry("miner", "煤矿仆从"),
            Map.entry("farmer", "小麦仆从"),
            Map.entry("lumberjack", "橡木仆从"),
            Map.entry("fisher", "钓鱼仆从"),
            Map.entry("slayer", "僵尸仆从"),
            Map.entry("rancher", "牛仆从"),
            Map.entry("cobble", "圆石仆从"),
            // 中文化第一版过渡 key
            Map.entry("煤矿", "煤矿仆从"),
            Map.entry("铁矿", "铁矿仆从"),
            Map.entry("铜矿", "铜矿仆从"),
            Map.entry("金矿", "金矿仆从"),
            Map.entry("红石矿", "红石仆从"),
            Map.entry("青金矿", "青金石仆从"),
            Map.entry("钻石", "钻石仆从"),
            Map.entry("绿宝石", "绿宝石仆从"),
            Map.entry("石英", "石英仆从"),
            Map.entry("黑曜石", "黑曜石仆从"),
            Map.entry("小麦", "小麦仆从"),
            Map.entry("胡萝卜", "胡萝卜仆从"),
            Map.entry("马铃薯", "马铃薯仆从"),
            Map.entry("南瓜", "南瓜仆从"),
            Map.entry("西瓜", "西瓜仆从"),
            Map.entry("甜菜", "甜菜仆从"),
            Map.entry("可可", "可可仆从"),
            Map.entry("下界疣", "下界疣仆从"),
            Map.entry("橡木", "橡木仆从"),
            Map.entry("白桦", "白桦仆从"),
            Map.entry("云杉", "云杉仆从"),
            Map.entry("丛林", "丛林仆从"),
            Map.entry("金合欢", "金合欢仆从"),
            Map.entry("深色橡木", "深色橡木仆从"),
            Map.entry("樱花", "樱花仆从"),
            Map.entry("红树", "红树仆从"),
            Map.entry("僵尸猎人", "僵尸仆从"),
            Map.entry("骷髅猎人", "骷髅仆从"),
            Map.entry("苦力怕猎人", "苦力怕仆从"),
            Map.entry("蛛网猎人", "蜘蛛仆从"),
            Map.entry("末影猎人", "末影人仆从"),
            Map.entry("烈焰猎人", "烈焰人仆从"),
            Map.entry("史莱姆猎人", "史莱姆仆从"),
            Map.entry("养牛", "牛仆从"),
            Map.entry("绵羊", "羊仆从"),
            Map.entry("蛋鸡", "鸡仆从"),
            Map.entry("生猪", "猪仆从"),
            Map.entry("兔子", "兔仆从"));

    private final String key;
    private final String displayName;
    private final MinionBehavior behavior;
    private final MinionCategory category;
    private final Material icon;

    private MinionType(String key, String displayName, MinionBehavior behavior,
                       MinionCategory category, Material icon) {
        this.key = key;
        this.displayName = displayName;
        this.behavior = behavior;
        this.category = category;
        this.icon = icon;
    }

    /** 构建一个类型实例（仅 ConfigLoader 装配时调用）。 */
    public static MinionType of(String key, String displayName, MinionBehavior behavior,
                                MinionCategory category, Material icon) {
        return new MinionType(key, displayName, behavior, category, icon);
    }

    /** 整体替换注册表（热重载安全）；空列表时回退到内置 fallback 单例。 */
    public static void loadAll(Collection<MinionType> types) {
        Map<String, MinionType> map = new ConcurrentHashMap<>();
        List<MinionType> order = new ArrayList<>();
        for (MinionType t : types) {
            if (t == null || t.key == null || t.key.isBlank()) {
                continue;
            }
            map.put(t.key.toLowerCase(Locale.ROOT), t);
            order.add(t);
        }
        if (order.isEmpty()) {
            map.put(FALLBACK.key, FALLBACK);
            order.add(FALLBACK);
        }
        registry = map;
        ordered = List.copyOf(order);
    }

    public static Optional<MinionType> fromKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String lower = key.toLowerCase(Locale.ROOT);
        MinionType hit = registry.get(lower);
        if (hit != null) {
            return Optional.of(hit);
        }
        // 旧英文 key 别名（存量数据兼容路径）
        String alias = LEGACY_ALIASES.get(lower);
        return alias == null ? Optional.empty()
                : Optional.ofNullable(registry.get(alias.toLowerCase(Locale.ROOT)));
    }

    /** 注册表为空时的兜底单例（未配置/未知 key 场景，绝不返回 null）。 */
    public static MinionType fallback() {
        return FALLBACK;
    }

    /** 全部已注册类型（保序快照）。 */
    public static Collection<MinionType> all() {
        return ordered;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public MinionBehavior behavior() {
        return behavior;
    }

    public MinionCategory category() {
        return category;
    }

    public Material icon() {
        return icon;
    }

    @Override
    public String toString() {
        return key;
    }
}
