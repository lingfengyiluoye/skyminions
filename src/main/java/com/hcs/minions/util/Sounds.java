package com.hcs.minions.util;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 事件音效（Hypixel 式反馈闭环：每个关键动作都有声音确认）。
 *
 * <p>与 {@link Fx}（ActionBar/Title/Sound 三件套的原始出口）分工：Fx 管「一条消息配一个音」，
 * 本类管「按事件查表播音」。音量集中在 config.yml 的 {@code sounds:} 段配置，
 * 单项置 0 即关闭；删段回退内置默认。</p>
 *
 * <pre>
 * sounds:
 *   place: 1.0        # 放置仆从
 *   pickup: 1.0       # 拾取仆从
 *   harvest: 0.35     # 仆从完成一次工作（仅播放给正在看该仆从 GUI 的玩家，避免满岛噪音）
 *   milestone: 1.0    # 收集里程碑达成
 *   sell: 0.5         # 自动售卖入账
 *   click: 0.6        # GUI 按钮点击
 *   module: 1.0       # 装备/卸下模块
 *   fuel-out: 0.8     # 限时燃料耗尽
 *   unlock: 0.9       # 缴纳解锁金币、解锁新类型
 * </pre>
 *
 * <p>线程契约：所有入口都经 {@link PlayerTasks} 投递到玩家自身线程
 * （{@code Player#playSound} 不是线程安全的，而调用方可能是 region 线程或虚拟线程）。</p>
 */
public final class Sounds {

    /**
     * 音效事件（一个事件 = 一个固定音色 + 可配置音量）。
     *
     * <p>音色用<b>名字字符串</b>而非 {@code Sound} 常量：{@code Sound} 枚举的类初始化
     * 依赖服务端 Registry，在单测/无服务端环境会抛异常。名字在 {@link #play} 播放时才
     * 解析，测试 therefore 可以只覆盖音量表而不触碰 Bukkit。</p>
     */
    public enum Cue {
        PLACE("BLOCK_NOTE_BLOCK_CHIME", 1.0f),
        PICKUP("ENTITY_ITEM_PICKUP", 0.8f),
        HARVEST("BLOCK_NOTE_BLOCK_HAT", 1.0f),
        MILESTONE("ENTITY_PLAYER_LEVELUP", 1.0f),
        SELL("ENTITY_EXPERIENCE_ORB_PICKUP", 1.4f),
        CLICK("UI_BUTTON_CLICK", 1.1f),
        MODULE("ITEM_ARMOR_EQUIP_GENERIC", 1.0f),
        FUEL_OUT("BLOCK_FIRE_EXTINGUISH", 1.0f),
        UNLOCK("ENTITY_PLAYER_LEVELUP", 0.8f);

        private final String soundName;
        private final float pitch;

        Cue(String soundName, float pitch) {
            this.soundName = soundName;
            this.pitch = pitch;
        }

        /** 原版音效枚举名（如 {@code UI_BUTTON_CLICK}）。 */
        public String soundName() {
            return soundName;
        }

        public float pitch() {
            return pitch;
        }
    }

    /** 内置默认音量（config.yml 缺 sounds 段时使用）。 */
    private static final Map<Cue, Float> DEFAULT_VOLUMES = defaultVolumes();

    private static volatile Map<Cue, Float> volumes = DEFAULT_VOLUMES;

    /** 插件实例（实体调度需要）；由组合根 {@link #init} 注入。 */
    private static volatile JavaPlugin plugin;

    private Sounds() {
    }

    /** 组合根装配期调用：注入插件实例并装载内置默认音量。 */
    public static void init(JavaPlugin javaPlugin) {
        plugin = javaPlugin;
        volumes = DEFAULT_VOLUMES;
    }

    private static Map<Cue, Float> defaultVolumes() {
        Map<Cue, Float> m = new EnumMap<>(Cue.class);
        m.put(Cue.PLACE, 1.0f);
        m.put(Cue.PICKUP, 1.0f);
        m.put(Cue.HARVEST, 0.35f); // 工作音极轻：只在有人盯着 GUI 时播，仍不能吵
        m.put(Cue.MILESTONE, 1.0f);
        m.put(Cue.SELL, 0.5f);
        m.put(Cue.CLICK, 0.6f);
        m.put(Cue.MODULE, 1.0f);
        m.put(Cue.FUEL_OUT, 0.8f);
        m.put(Cue.UNLOCK, 0.9f);
        return m;
    }

    /** 热重载：按 config.yml 的 sounds 段覆盖音量；缺段/全非法时保留内置默认。 */
    public static void reload(ConfigurationSection section) {
        if (section == null) {
            volumes = DEFAULT_VOLUMES;
            return;
        }
        Map<Cue, Float> parsed = new EnumMap<>(Cue.class);
        for (Cue cue : Cue.values()) {
            String key = cue.name().toLowerCase(Locale.ROOT).replace('_', '-');
            if (section.contains(key)) {
                double v = section.getDouble(key, DEFAULT_VOLUMES.get(cue));
                parsed.put(cue, (float) Math.max(0.0, Math.min(2.0, v)));
            } else {
                parsed.put(cue, DEFAULT_VOLUMES.get(cue));
            }
        }
        volumes = parsed;
    }

    /** 当前音量表（快照，供测试/调试）。 */
    public static Map<Cue, Float> volumes() {
        return volumes;
    }

    /** 播放指定事件音效（音量 0 或玩家离线时静默跳过）。 */
    public static void play(Player player, Cue cue) {
        if (player == null || cue == null) {
            return;
        }
        float volume = volumes.getOrDefault(cue, DEFAULT_VOLUMES.getOrDefault(cue, 1.0f));
        if (volume <= 0f) {
            return;
        }
        JavaPlugin owner = plugin;
        if (owner == null) {
            return; // 未装配（单测/早期调用）：不播音也不抛异常
        }
        // 音色在此处才解析：Sound 枚举类初始化依赖服务端 Registry
        Sound sound;
        try {
            sound = Sound.valueOf(cue.soundName());
        } catch (IllegalArgumentException e) {
            Logs.warn("未知音效名 {}（事件 {}），已跳过", cue.soundName(), cue);
            return;
        }
        PlayerTasks.run(owner, player, p ->
                p.playSound(p.getLocation(), sound, SoundCategory.MASTER, volume, cue.pitch()));
    }

    // ---- 具名入口（调用方不需要知道枚举） ----

    public static void place(Player player) {
        play(player, Cue.PLACE);
    }

    public static void pickup(Player player) {
        play(player, Cue.PICKUP);
    }

    public static void harvest(Player player) {
        play(player, Cue.HARVEST);
    }

    public static void milestone(Player player) {
        play(player, Cue.MILESTONE);
    }

    public static void sell(Player player) {
        play(player, Cue.SELL);
    }

    public static void click(Player player) {
        play(player, Cue.CLICK);
    }

    public static void module(Player player) {
        play(player, Cue.MODULE);
    }

    public static void fuelOut(Player player) {
        play(player, Cue.FUEL_OUT);
    }

    public static void unlock(Player player) {
        play(player, Cue.UNLOCK);
    }
}
