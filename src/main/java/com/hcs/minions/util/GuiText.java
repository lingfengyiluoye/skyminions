package com.hcs.minions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GUI 文案模板引擎（gui.yml）：仆从 GUI 内所有卡片/按钮的标题与 lore 全部可自定义。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>模板支持 MiniMessage 标签（{@code <gold>} {@code <green>} …）与命名占位符 {@code {xxx}}；</li>
 *   <li><b>可选行</b>：lore 行内存在「本次未提供值」的占位符时，整行自动隐藏
 *       （例如未配置稀有掉落时，信息卡的稀有掉落行不渲染）；</li>
 *   <li>标题中未提供值的占位符原样保留；</li>
 *   <li>渲染产物统一关闭原版物品名/Lore 的<b>默认斜体</b>（Paper 的 Adventure
 *       Component 未显式设置 {@code ITALIC=false} 时，客户端按原版默认斜体渲染）；</li>
 *   <li>gui.yml 缺失的键回退到内置默认模板，可 {@code /minion reload} 热重载；</li>
 *   <li>模板解析失败（标签未配平等）不抛出：降级为去标签纯文本并告警，
 *       避免一行写错导致整个 GUI 打不开（与 ConfigLoader 的「回退 + 告警」约定一致）。</li>
 * </ul>
 */
public final class GuiText {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern VAR = Pattern.compile("\\{([a-zA-Z0-9_]+)}");
    /** 去掉 MiniMessage 标签（解析失败时的纯文本降级用）。 */
    private static final Pattern TAG = Pattern.compile("<[^<>]*>");

    /** 从 gui.yml 加载的模板（key -> String 标题 或 List&lt;String&gt; lore 行；不含 layout. 段）。 */
    private static volatile Map<String, Object> templates = Map.of();
    /** 已告警过的模板键：同一键只告警一次，防止渲染路径刷日志（reload 时重置）。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private GuiText() {
    }

    /** 加载 gui.yml（不存在则导出默认文件）；可重复调用（热重载）。 */
    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "gui.yml");
        if (!file.exists()) {
            plugin.saveResource("gui.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        GuiLayout.load(yaml); // 同一文件的 layout 段：槽位/材质布局（与文案同步热重载）
        WARNED.clear();
        Map<String, Object> loaded = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            // layout 段属布局配置（由 GuiLayout 解析），不并入文案模板表，避免与文案键同名时误读
            if (key.startsWith("layout.")) {
                continue;
            }
            if (yaml.isString(key)) {
                loaded.put(key, yaml.getString(key));
            } else if (yaml.isList(key)) {
                loaded.put(key, yaml.getStringList(key));
            }
        }
        templates = loaded;
        Logs.info("GUI 文案已加载（{} 个模板，来自 gui.yml）", loaded.size());
    }

    // ------------------------------------------------------------------
    // 渲染 API
    // ------------------------------------------------------------------

    /** 渲染标题：缺失占位符原样保留。 */
    public static Component title(String key, Map<String, String> vars) {
        String tpl = string(key);
        if (tpl == null) {
            return noItalic(Component.text(key));
        }
        return noItalic(parse(key, substitute(tpl, vars)));
    }

    public static Component title(String key) {
        return title(key, Map.of());
    }

    /** 渲染 lore：行内存在未提供占位符 → 整行隐藏（可选行机制）。 */
    public static List<Component> lore(String key, Map<String, String> vars) {
        List<String> lines = list(key);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String resolved = substituteOptional(line, vars);
            if (resolved == null) {
                continue;
            }
            out.add(noItalic(parse(key, resolved)));
        }
        return out;
    }

    public static List<Component> lore(String key) {
        return lore(key, Map.of());
    }

    // ------------------------------------------------------------------
    // 行内嵌行（lore 片段）
    // ------------------------------------------------------------------

    /**
     * 原始模板串（占位符已替换、未经 MiniMessage 解析）。
     *
     * <p>用于「行内嵌行」场景：材料对比行等 lore 片段要先渲染成 MiniMessage 串，
     * 再作为 {@code {m1}} 等占位符的值注入外层模板。业务代码不得再硬编码这类
     * 片段的颜色/文案——一律走 gui.yml 模板（{@code {color}} 等占位符传颜色标签）。</p>
     */
    public static String raw(String key, Map<String, String> vars) {
        String tpl = string(key);
        return tpl == null ? "" : substitute(tpl, vars);
    }

    // ------------------------------------------------------------------
    // MiniMessage 解析（带降级兜底）
    // ------------------------------------------------------------------

    /** 解析 MiniMessage 模板；标签未配平等解析失败时降级为去标签纯文本，不让 GUI 打不开。 */
    private static Component parse(String key, String resolved) {
        try {
            return MM.deserialize(resolved);
        } catch (RuntimeException e) {
            if (WARNED.add(key)) {
                Logs.warn("gui.yml 模板 {} 解析失败（MiniMessage 标签可能未配平），已降级为纯文本: {}",
                        key, e.getMessage());
            }
            return Component.text(TAG.matcher(resolved).replaceAll(""));
        }
    }

    // ------------------------------------------------------------------
    // 模板获取（gui.yml 优先，缺键回退内置默认）
    // ------------------------------------------------------------------

    private static String string(String key) {
        Object v = templates.get(key);
        if (v instanceof String s) {
            return s;
        }
        Object d = Defaults.MAP.get(key);
        return d instanceof String s ? s : null;
    }

    private static List<String> list(String key) {
        Object v = templates.get(key);
        if (v instanceof List<?> l && !l.isEmpty()) {
            return l.stream().map(String::valueOf).toList();
        }
        Object d = Defaults.MAP.get(key);
        return d instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    // ------------------------------------------------------------------
    // 占位符替换
    // ------------------------------------------------------------------

    /** 全部占位符替换；缺失保留 {原文}（标题用）。 */
    private static String substitute(String tpl, Map<String, String> vars) {
        Matcher m = VAR.matcher(tpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? m.group(0) : val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 全部占位符必须已有值，否则返回 null（该行隐藏）。 */
    private static String substituteOptional(String line, Map<String, String> vars) {
        Matcher m = VAR.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = vars.get(m.group(1));
            if (val == null) {
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 关闭原版物品名/Lore 默认斜体。 */
    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------
    // 内置默认模板（gui.yml 缺键回退；与默认 gui.yml 内容一致）
    // ------------------------------------------------------------------

    private static final class Defaults {
        private static final Map<String, Object> MAP = new HashMap<>();
        /** 分隔线（Hypixel 式细线：删除线铺在空格上，比 ▬ 更干净连续）。 */
        private static final String LINE = "<strikethrough><dark_gray>                    </dark_gray></strikethrough>";

        static {
            MAP.put("title", "{name}");
            MAP.put("info.title", "<gradient:#FFD54A:#FF9C2A>✦ {name}</gradient> <gray>·</gray> <yellow>等级 {tier}</yellow>");
            // 状态行（作为 lore 中 {status} 占位符的值注入）
            MAP.put("info.status-halted", "<red>⚠ 仓库已满 · 停工中</red>");
            MAP.put("info.status-working", "<green>✅ 运行中</green>");
            MAP.put("info.halted-tip", "<gray>取货或开启自动售卖后恢复工作</gray>");
            // 「下次工作」右半行：停机时不注入（整行只剩累计，不留空槽）
            MAP.put("info.next-pair", "   <gray>下次</gray> <dark_gray>»</dark_gray> <white>{next} 秒</white>");
            MAP.put("info.fuel-hint", "<dark_gray>空手点击燃料槽查看燃料指引</dark_gray>");
            MAP.put("info.lore", List.of(
                    "{status}",
                    "<gray>速度</gray> <dark_gray>»</dark_gray> <white>{speed} 秒/次</white>   "
                            + "<gray>产出</gray> <dark_gray>»</dark_gray> <white>约 {rate} 件/时</white>",
                    "<gray>范围</gray> <dark_gray>»</dark_gray> <white>{range}</white>   "
                            + "<gray>存储</gray> <dark_gray>»</dark_gray> <white>{storage}</white> {storage_bar}",
                    "<strikethrough><dark_gray>                    </dark_gray></strikethrough>",
                    "<yellow>累计</yellow> <dark_gray>»</dark_gray> <yellow>{total} 件</yellow>{next_pair}",
                    "<gray>稀有</gray> <dark_gray>»</dark_gray> <light_purple>{rare}</light_purple> "
                            + "<dark_gray>({rare_chance}%)</dark_gray>",
                    "<gray>{next_tier} 级解锁</gray> <dark_gray>»</dark_gray> <white>{unlock_mats}</white>",
                    "{halted_tip}",
                    "{hint}"
            ));
            MAP.put("head.title", "<gradient:#FFE98A:#FFB347>{name}</gradient> <gray>·</gray> <yellow>等级 {tier}</yellow>");
            MAP.put("head.lore", List.of(
                    LINE,
                    "<gray>主人</gray> <dark_gray>»</dark_gray> <white>{owner}</white>",
                    "<gray>皮肤</gray> <dark_gray>»</dark_gray> <light_purple>{skin}</light_purple>",
                    "",
                    "<dark_gray>放置于私岛自动采集资源</dark_gray>",
                    "<yellow>右键</yellow> <gray>打开仓库</gray> <dark_gray>·</dark_gray> <yellow>潜行右键</yellow> <gray>拾取</gray>",
                    LINE
            ));
            MAP.put("upgrade.title", "<green>⬆ 升级到等级 {tier}</green>");
            MAP.put("upgrade.lore", List.of(
                    LINE,
                    "<gray>速度</gray> <dark_gray>»</dark_gray> <gray>{speed_now}s</gray> <dark_gray>➜</dark_gray> <green>{speed_next}s</green>",
                    "",
                    "<gray>所需材料</gray>",
                    "{m1}",
                    "{m2}",
                    "{m3}",
                    "{m4}",
                    "{body}",
                    "{more}",
                    LINE,
                    "<green>✔ 材料充足</green> <dark_gray>·</dark_gray> <yellow>点击合成 ▶</yellow>"
            ));
            MAP.put("upgrade-lack.title", "<yellow>⬆ 升级到等级 {tier}</yellow>");
            MAP.put("upgrade-lack.lore", List.of(
                    LINE,
                    "<gray>速度</gray> <dark_gray>»</dark_gray> <gray>{speed_now}s</gray> <dark_gray>➜</dark_gray> <green>{speed_next}s</green>",
                    "",
                    "<gray>所需材料</gray>",
                    "{m1}",
                    "{m2}",
                    "{m3}",
                    "{m4}",
                    "{body}",
                    "{more}",
                    LINE,
                    "<yellow>点击打开合成界面</yellow> <dark_gray>（材料放入合成格）</dark_gray>"
            ));
            MAP.put("upgrade-max.title", "<gold>✦ 已满级</gold>");
            MAP.put("upgrade-max.lore", List.of(
                    LINE,
                    "<gray>已达最高等级</gray> <dark_gray>»</dark_gray> <gold>{tier}</gold>",
                    LINE
            ));
            // 升级按钮内的「行内嵌行」片段（经 GuiText.raw 渲染后注入 {m1}~{m4}/{body}/{more}）
            MAP.put("upgrade.material-line",
                    "<dark_gray>· {color}{name} <white>×{need}</white> <gray>已有 {owned}</gray>");
            MAP.put("upgrade.body-line",
                    "<dark_gray>· <aqua>仆从本体</aqua> <white>×1</white> <gray>（同类型当前等级）</gray>");
            MAP.put("upgrade.more-line",
                    "<dark_gray>…等共 {count} 种材料（点击打开合成界面查看）</dark_gray>");
            MAP.put("fuel.title", "<gold>⛽ 燃料槽</gold>");
            // 行内嵌行片段（经 GuiText.raw 渲染后注入 {left}/{perm}/{mult}）
            MAP.put("fuel.left-line",
                    "<gray>限时</gray> <dark_gray>»</dark_gray> <green>+{boost}%</green> "
                            + "<dark_gray>·</dark_gray> <white>{fraction}</white> {bar}");
            MAP.put("fuel.perm-line",
                    "<gray>永久</gray> <dark_gray>»</dark_gray> <green>加速 +{perm}%</green>");
            MAP.put("fuel.mult-line",
                    "<gray>倍率</gray> <dark_gray>»</dark_gray> <light_purple>{mult}</light_purple> "
                            + "<dark_gray>·</dark_gray> <white>{fraction}</white> {bar}");
            MAP.put("fuel.lore", List.of(
                    "{left}",
                    "{perm}",
                    "{mult}",
                    "<dark_gray>{nofuel}当前无燃料（仆从仍会工作）</dark_gray>",
                    "<dark_gray>空手点击查看指引 · 手持燃料点击即生效</dark_gray>"
            ));
            MAP.put("skin.title", "<light_purple>✎ 皮肤</light_purple>");
            MAP.put("skin.lore", List.of(
                    LINE,
                    "<gray>当前</gray> <dark_gray>»</dark_gray> <light_purple>{current}</light_purple>",
                    "<gray>下一个</gray> <dark_gray>»</dark_gray> <gray>{next}</gray>",
                    LINE,
                    "<yellow>点击切换 ▶</yellow>"
            ));
            MAP.put("module-locked.title", "<dark_gray>🔒 模块槽 #{n}</dark_gray>");
            MAP.put("module-locked.lore", List.of(
                    LINE,
                    "<red>✖ 未解锁</red>",
                    "<gray>升级到等级</gray> <yellow>{tier}</yellow> <gray>解锁</gray>",
                    LINE
            ));
            MAP.put("module-equipped.title", "<aqua>◈ {name}</aqua>");
            MAP.put("module-equipped.lore", List.of(
                    LINE,
                    "<gray>{desc}</gray>",
                    "",
                    "<green>● 已装备</green>",
                    "<yellow>空手点击卸下 ▶</yellow>",
                    LINE
            ));
            MAP.put("module-empty.title", "<gray>◇ 模块槽 #{n}</gray>");
            MAP.put("module-empty.lore", List.of(
                    LINE,
                    "<dark_gray>○ 空槽</dark_gray>",
                    "<gray>手持模块物品点击装备</gray>",
                    "<dark_gray>可用 /minion upgrade 获取</dark_gray>",
                    LINE
            ));
            MAP.put("collect.title", "<gold>✦ 收集全部</gold>");
            MAP.put("collect.lore", List.of(
                    LINE,
                    "<gray>当前仓存</gray> <dark_gray>»</dark_gray> <yellow>{count}</yellow> <dark_gray>件</dark_gray>",
                    "<dark_gray>将仓库所有物品转移到背包</dark_gray>",
                    LINE,
                    "<yellow>点击收集 ▶</yellow>"
            ));
            MAP.put("pickup.title", "<red>✖ 拾取仆从</red>");
            MAP.put("pickup.lore", List.of(
                    LINE,
                    "<gray>移除仆从并收回物品栏</gray>",
                    "<dark_gray>仓库、模块与皮肤会一并保留</dark_gray>",
                    "<dark_gray>潜行右键仆从也可拾取</dark_gray>",
                    LINE,
                    "<yellow>点击拾取 ▶</yellow>"
            ));
            MAP.put("close.title", "<red>✖ 关闭</red>");
            MAP.put("locked-slot.title", "<dark_gray>🔒 锁定槽位</dark_gray>");
            MAP.put("locked-slot.lore", List.of(
                    "<gray>升级仆从以解锁更多存储</gray>"
            ));

            // ---- 图鉴 GUI（/minions） ----
            MAP.put("collection-gui.title", "<gradient:#5EE7FF:#2A9DFF>仆从图鉴</gradient>");
            MAP.put("collection-gui.close.title", "<red>✖ 关闭</red>");
            MAP.put("collection-gui.filter.title", "<aqua>{sel}{name}</aqua>");
            MAP.put("collection-gui.card.title", "<gold>{name}</gold>");
            MAP.put("collection-gui.card.lore", List.of(
                    "<gray>分类</gray> <dark_gray>»</dark_gray> <white>{category}</white>   "
                            + "<gray>等级</gray> <dark_gray>»</dark_gray> <yellow>{tier}</yellow> "
                            + "<dark_gray>/</dark_gray> <yellow>{max_tier}</yellow>",
                    "<gray>放置</gray> <dark_gray>»</dark_gray> <white>{placed} 个</white>   "
                            + "<gray>产出</gray> <dark_gray>»</dark_gray> <yellow>{produced} 件</yellow>",
                    "<gray>收集</gray> <dark_gray>»</dark_gray> <white>{collected}/{collect_next}</white> {collect_bar}",
                    "<strikethrough><dark_gray>                    </dark_gray></strikethrough>",
                    "{r1}",
                    "{r2}",
                    "{r3}",
                    "{more}",
                    "<yellow>点击打开升级合成界面 ▶</yellow>"
            ));
            MAP.put("collection-gui.locked.title", "<dark_gray>??? 未解锁</dark_gray>");
            MAP.put("collection-gui.locked.lore", List.of(
                    LINE,
                    "<gray>分类</gray> <dark_gray>»</dark_gray> <aqua>{category}</aqua>",
                    "",
                    "<red>✖ 尚未解锁</red>",
                    "<gray>{product} 收集</gray> <dark_gray>»</dark_gray> <yellow>{collected}</yellow> <dark_gray>/</dark_gray> <white>{need}</white>",
                    LINE
            ));
            MAP.put("collection-gui.prev.title", "<green>◀ 上一页</green>");
            MAP.put("collection-gui.next.title", "<green>下一页 ▶</green>");
            MAP.put("collection-gui.progress.title", "<gold>✦ 图鉴进度 {unlocked}/{total}</gold> {bar}");
            MAP.put("collection-gui.progress.lore", List.of(
                    "<gray>第</gray> <white>{page}</white> <dark_gray>/</dark_gray> <white>{pages}</white> <gray>页</gray>",
                    "<dark_gray>收集资源解锁更多仆从类型</dark_gray>"
            ));
            MAP.put("collection-gui.recipe-header.title", "<gold>▼ {name} · 下一级配方</gold>");
            MAP.put("collection-gui.recipe-none.title", "<gray>尚未放置过该类型仆从，暂无升级记录</gray>");
            MAP.put("collection-gui.recipe-max.title", "<green>已达最高等级（{tier}）</green>");
            // 图鉴卡片内的「行内嵌行」片段（经 GuiText.raw 渲染后注入 {r1}~{r3}/{more}）
            MAP.put("collection-gui.card.recipe-line", "<dark_gray>· {name} ×{need}</dark_gray>");
            MAP.put("collection-gui.card.body-line", "<dark_gray>· {name} 等级 {tier} ×1</dark_gray>");
            MAP.put("collection-gui.card.more-line",
                    "<dark_gray>…等共 {count} 种材料（详见升级合成界面）</dark_gray>");
            // 聊天栏配方详情（点击未放置类型的卡片时输出）
            MAP.put("collection-gui.chat.recipe-line", "<dark_gray>· <white>{name}</white> ×<yellow>{need}</yellow></dark_gray>");
            MAP.put("collection-gui.chat.body-line", "<dark_gray>· <white>{name}</white> 等级 <yellow>{tier}</yellow> ×1</dark_gray>");

            // ---- 燃料选择 GUI ----
            MAP.put("fuel-gui.title", "<gold>⛽ 选择燃料</gold>");
            MAP.put("fuel-gui.empty.title", "<gray>背包中没有可用燃料</gray>");
            MAP.put("fuel-gui.close.title", "<red>✖ 关闭</red>");
            MAP.put("fuel-gui.option.title", "<yellow>{name}</yellow>");
            MAP.put("fuel-gui.option.lore", List.of(
                    LINE,
                    "<gray>加速</gray> <dark_gray>»</dark_gray> <green>+{boost}%</green>",
                    "<gray>产出倍率</gray> <dark_gray>»</dark_gray> <light_purple>{mult}</light_purple>",
                    "<gray>持续</gray> <dark_gray>»</dark_gray> <white>{duration}</white> <dark_gray>/ 个</dark_gray>",
                    "<gray>背包数量</gray> <dark_gray>»</dark_gray> <yellow>{count}</yellow>",
                    LINE,
                    "<yellow>点击安装全部</yellow> <dark_gray>·</dark_gray> <yellow>潜行安装 1 个</yellow>"
            ));
            MAP.put("fuel-gui.status.title", "<gold>当前燃料状态</gold>");
            MAP.put("fuel-gui.status.lore", List.of(
                    LINE,
                    "<gray>永久加速</gray> <dark_gray>»</dark_gray> <green>+{perm}%</green>",
                    "<gray>限时剩余</gray> <dark_gray>»</dark_gray> <yellow>{left}</yellow>",
                    "<dark_gray>{none}当前无燃料</dark_gray>",
                    LINE,
                    "<dark_gray>潜行点击卸下限时燃料（永久不可卸下）</dark_gray>"
            ));

            // ---- 升级合成 GUI（Hypixel 式 3×3 合成玩法） ----
            MAP.put("craft-gui.title", "<gold>⚒ 升级合成 · {name} 等级 {tier}</gold>");
            MAP.put("craft-gui.info.title", "<gold>✦ 升级配方 · 等级 {tier} ➜ {next}</gold>");
            MAP.put("craft-gui.info.lore", List.of(
                    LINE,
                    "<gray>所需材料</gray>",
                    "{m1}",
                    "{m2}",
                    "{m3}",
                    "{m4}",
                    "{body}",
                    "{more}",
                    LINE,
                    "<gray>把材料与本体放入左侧合成格</gray>",
                    "<yellow>材料齐后点击右侧产物合成 ▶</yellow>"
            ));
            MAP.put("craft-gui.result-lack.title", "<gray>○ 材料未集齐</gray>");
            MAP.put("craft-gui.result-lack.lore", List.of(
                    "<gray>按左侧配方放入材料与本体</gray>",
                    "<yellow>潜行点击信息卡可一键填充</yellow>"
            ));
            // 合成界面内的「行内嵌行」片段（经 GuiText.raw 渲染后注入 {m1}~{m4}/{body}/{more}）
            MAP.put("craft-gui.material-line",
                    "<dark_gray>· {color}{name} <white>×{need}</white> <gray>已放 {placed}</gray>");
            MAP.put("craft-gui.body-line",
                    "<dark_gray>· {color}仆从本体 <white>×1</white> <gray>已放 {placed}</gray>");
            MAP.put("craft-gui.more-line",
                    "<dark_gray>…等共 {count} 种材料（其余请直接放入合成格）</dark_gray>");
            MAP.put("craft-gui.arrow.title", "<dark_gray>➜ 合成</dark_gray>");
            MAP.put("craft-gui.back.title", "<yellow>◀ 返回仆从界面</yellow>");
            // 升级成功的 Title 高光（主+副）
            MAP.put("craft-gui.success.title", "<gold>✔ 升级成功</gold>");
            MAP.put("craft-gui.success.subtitle", "<gray>当前 等级 {tier}</gray>");
            MAP.put("craft-gui.guide.title", "<gold>📖 材料指南</gold>");
            MAP.put("craft-gui.guide.lore", List.of(
                    LINE,
                    "<gray>查看本级材料的获取方式</gray>",
                    "<dark_gray>合成配方 / 原版名称 / 其他来源</dark_gray>",
                    LINE,
                    "<yellow>点击打开图形化合成预览 ▶</yellow>"
            ));

            // ---- 合成预览 GUI ----
            MAP.put("preview.title", "<gold>{name} · 合成预览</gold>");
            MAP.put("preview.info.title", "<gold>✦ 材料信息</gold>");
            MAP.put("preview.info.vanilla", "<gray>原版名称</gray> <dark_gray>»</dark_gray> <white>{vanilla}</white>");
            MAP.put("preview.info.howto", "<gray>获取</gray> <dark_gray>»</dark_gray> <green>{howto}</green>");
            MAP.put("preview.arrow.title", "<yellow>➜ 合成</yellow>");
            MAP.put("preview.prev.title", "<green>◀ 上一种材料</green>");
            MAP.put("preview.next.title", "<green>下一种材料 ▶</green>");
            MAP.put("preview.close.title", "<red>✖ 关闭</red>");
            MAP.put("preview.nocraft.title", "<red>✖ 不可合成</red>");

            // ---- 材料指南清单 GUI（两级导航第一级） ----
            MAP.put("guide-list.title", "<gold>📖 材料指南 · {name}</gold> <dark_gray>{tier}➜{next}</dark_gray>");
            MAP.put("guide-list.item.name-craft", "<yellow>{material}</yellow> <dark_gray>×</dark_gray><white>{need}</white>");
            MAP.put("guide-list.item.name-nocraft", "<gray>{material}</gray> <dark_gray>×</dark_gray><white>{need}</white>");
            MAP.put("guide-list.item.lore-craft", List.of(
                    LINE,
                    "<gray>原版</gray> <dark_gray>»</dark_gray> <white>{vanilla}</white>",
                    "<gray>已有</gray> <dark_gray>»</dark_gray> <white>{owned}</white>",
                    LINE,
                    "<green>▶ 点击查看合成方式</green>"
            ));
            MAP.put("guide-list.item.lore-nocraft", List.of(
                    LINE,
                    "<gray>原版</gray> <dark_gray>»</dark_gray> <white>{vanilla}</white>",
                    "<gray>已有</gray> <dark_gray>»</dark_gray> <white>{owned}</white>",
                    "",
                    "<red>✖ 不可合成</red>",
                    "<dark_gray>{source}</dark_gray>",
                    LINE
            ));
            MAP.put("guide-list.item.name-enchanted", "<aqua>{material}</aqua> <dark_gray>×</dark_gray><white>{need}</white>");
            MAP.put("guide-list.item.lore-enchanted", List.of(
                    LINE,
                    "<gray>附魔资源</gray> <dark_gray>»</dark_gray> <white>{ratio} × {base} → 1</white>",
                    "<gray>已有</gray> <dark_gray>»</dark_gray> <white>{owned}</white>",
                    LINE,
                    "<dark_gray>仅能由「超级压缩 3000」模块或手动压缩得出</dark_gray>",
                    "<green>▶ 点击查看压缩方式（可当场压缩）</green>"
            ));
            MAP.put("guide-list.close.title", "<red>✖ 关闭</red>");

            // ---- 升级材料总览 GUI（/minion materials） ----
            MAP.put("materials-gui.title", "<gradient:#FFD54A:#FF9C2A>升级材料总览 <dark_gray>{page}/{pages}</dark_gray></gradient>");
            MAP.put("materials-gui.close.title", "<red>✖ 关闭</red>");
            MAP.put("materials-gui.prev.title", "<green>◀ 上一页</green>");
            MAP.put("materials-gui.next.title", "<green>下一页 ▶</green>");
            MAP.put("materials-gui.card.title", "<aqua>{name}</aqua>");
            MAP.put("materials-gui.card.lore", List.of(
                    LINE,
                    "<gray>附魔资源 · 仆从升级材料</gray>",
                    "<gray>换算</gray> <dark_gray>»</dark_gray> <white>{ratio} × {base} → 1</white>",
                    "",
                    "<green>▶ 点击查看合成方式</green>",
                    LINE
            ));
            MAP.put("materials-gui.detail.title", "<gold>{name} · 合成预览</gold>");
            MAP.put("materials-gui.detail.info.title", "<gold>✦ 合成 {name}</gold>");
            MAP.put("materials-gui.detail.info.lore", List.of(
                    LINE,
                    "<gray>需要</gray> <dark_gray>»</dark_gray> <white>{ratio} 个 {base}</white>",
                    "<green>▶ 点击右侧成品：从背包即时压缩 1 个</green>",
                    "<dark_gray>仆从装「超级压缩 3000」模块可自动压缩</dark_gray>",
                    "<gray>背包现有</gray> <dark_gray>»</dark_gray> <white>{owned}</white>"
                            + " <dark_gray>÷ {ratio} = 可压缩 {can} 个</dark_gray>",
                    LINE
            ));
            MAP.put("materials-gui.detail.arrow.title", "<yellow>➜ 合成</yellow>");
            MAP.put("materials-gui.detail.result-hint", "<green>▶ 点击从背包压缩 1 个（需 {ratio} 个{base}）</green>");
            MAP.put("materials-gui.back.title", "<yellow>◀ 返回总览</yellow>");

            // ---- 稀有掉落 Title（仆从触发专属稀有掉落时的高光时刻） ----
            MAP.put("rare-drop.title", "<light_purple>✦ 稀有掉落!</light_purple>");
            MAP.put("rare-drop.subtitle", "<gray>{name}</gray>");

            // ---- 物品自身 Lore（非 GUI 卡片，但同属玩家可见展示内容） ----
            // 附魔资源物品
            MAP.put("enchanted.title", "<aqua>{name}</aqua>");
            MAP.put("enchanted.lore", List.of(
                    "<dark_gray>附魔资源 · 相当于 {ratio} 个{base}</dark_gray>",
                    "<dark_gray>仆从升级材料</dark_gray>"
            ));
            // 模块物品
            MAP.put("module-item.title", "<aqua>{name}</aqua>");
            MAP.put("module-item.lore", List.of(
                    "<gray>{desc}</gray>",
                    "<dark_gray>手持点击仆从的模块槽装备</dark_gray>"
            ));
            // 仆从生成物（手持物品）
            MAP.put("minion-item.title", "<gold>{name} {tier}</gold>");
            MAP.put("minion-item.lore", List.of(
                    "<gray>右键方块放置仆从</gray>",
                    "<dark_gray>{name}</dark_gray>"
            ));
            // 材料指南（聊天栏指引行）
            MAP.put("guide.chat-head", "<white>· {name}</white>");
            MAP.put("guide.chat-source", "<dark_gray>  [{craftable}]</dark_gray> {howto}");
            MAP.put("guide.chat-source-fallback", "<gray>  常规途径获取（挖掘/击杀/交易）</gray>");
        }

        private Defaults() {
        }
    }
}
