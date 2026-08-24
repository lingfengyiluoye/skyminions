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
 *   <li>gui.yml 缺失的键回退到内置默认模板，可 {@code /minion reload} 热重载。</li>
 * </ul>
 */
public final class GuiText {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern VAR = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    /** 从 gui.yml 加载的模板（key -> String 标题 或 List&lt;String&gt; lore 行）。 */
    private static volatile Map<String, Object> templates = Map.of();

    private GuiText() {
    }

    /** 加载 gui.yml（不存在则导出默认文件）；可重复调用（热重载）。 */
    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "gui.yml");
        if (!file.exists()) {
            plugin.saveResource("gui.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, Object> loaded = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
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
        return noItalic(MM.deserialize(substitute(tpl, vars)));
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
            out.add(noItalic(MM.deserialize(resolved)));
        }
        return out;
    }

    public static List<Component> lore(String key) {
        return lore(key, Map.of());
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
        private static final String LINE = "▬▬▬▬▬▬▬▬▬▬▬▬▬▬";

        static {
            MAP.put("title", "仆从 · {name}");
            MAP.put("info.title", "<gold>✦ {name} · 等级 {tier}</gold>");
            MAP.put("info.lore", List.of(
                    "<gray>状态: <green>● 工作中</green></gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>速度 <green>{speed} 秒/次</green></dark_gray>",
                    "<dark_gray>产出 <green>≈ {rate} 件/小时</green></dark_gray>",
                    "<dark_gray>范围 <aqua>{range}</aqua></dark_gray>",
                    "<dark_gray>存储 <white>{storage} 件 / {slots} 格</white></dark_gray>",
                    "<dark_gray>稀有掉落 <light_purple>{rare} ({rare_chance}%)</light_purple></dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>累计产出 <yellow>{total} 件</yellow></dark_gray>",
                    "<dark_gray>下次工作 <green>{next} 秒后</green></dark_gray>"
            ));
            MAP.put("head.title", "<yellow>{name} · 等级 {tier}</yellow>");
            MAP.put("head.lore", List.of(
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>主人: <white>{owner}</white></dark_gray>",
                    "<dark_gray>皮肤: <light_purple>{skin}</light_purple></dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<gray>放置于私岛自动采集资源</gray>",
                    "<dark_gray>右键打开仓库 · 潜行右键拾取</dark_gray>"
            ));
            MAP.put("upgrade.title", "<gold>升级到等级 {tier}</gold>");
            MAP.put("upgrade.lore", List.of(
                    "<dark_gray>速度: <gray>{speed_now}秒</gray> <dark_gray>→</dark_gray> <green>{speed_next}秒</green> <dark_gray>/次</dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "{m1}",
                    "{m2}",
                    "{m3}",
                    "{body}",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<yellow>点击升级 ▶</yellow>"
            ));
            MAP.put("upgrade-lack.title", "<gold>升级到等级 {tier}</gold>");
            MAP.put("upgrade-lack.lore", List.of(
                    "<dark_gray>速度: <gray>{speed_now}秒</gray> <dark_gray>→</dark_gray> <green>{speed_next}秒</green> <dark_gray>/次</dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "{m1}",
                    "{m2}",
                    "{m3}",
                    "{body}",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<gray>材料齐后点击升级</gray>"
            ));
            MAP.put("upgrade-max.title", "<gold>升级</gold>");
            MAP.put("upgrade-max.lore", List.of(
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<red>已达最高等级</red>",
                    "<gray>最高等级（{tier}）</gray>"
            ));
            MAP.put("fuel.title", "<gray>燃料槽</gray>");
            MAP.put("fuel.lore", List.of(
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>永久加速 <green>+{perm}%</green></dark_gray>",
                    "<dark_gray>限时剩余 <yellow>{left} 秒</yellow> <green>({timed}%)</green></dark_gray>",
                    "<gray>{nofuel}当前无燃料（仆从仍会工作）</gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<yellow>⚡ 手持燃料点击此槽 · 立即生效</yellow>",
                    "<dark_gray>限时: <gray>煤炭/岩浆桶/烈焰棒</gray></dark_gray>",
                    "<dark_gray>永久: <gray>岩浆膏/荧石粉/日光传感器</gray></dark_gray>",
                    "<dark_gray>空手点击查看燃料指引 · 手持燃料右键小人也可添加</dark_gray>"
            ));
            MAP.put("skin.title", "<light_purple>皮肤</light_purple>");
            MAP.put("skin.lore", List.of(
                    "<dark_gray>当前: <light_purple>{current}</light_purple></dark_gray>",
                    "<dark_gray>下一个: <gray>{next}</gray></dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<yellow>点击切换 ▶</yellow>"
            ));
            MAP.put("module-locked.title", "<dark_gray>模块槽 #{n}</dark_gray>");
            MAP.put("module-locked.lore", List.of(
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<red>✖ 未解锁</red>",
                    "<gray>升级到等级 {tier} 解锁</gray>"
            ));
            MAP.put("module-equipped.title", "<aqua>{name}</aqua>");
            MAP.put("module-equipped.lore", List.of(
                    "<gray>{desc}</gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<green>● 已装备</green>",
                    "<yellow>空手点击卸下 ▶</yellow>"
            ));
            MAP.put("module-empty.title", "<gray>模块槽 #{n}</gray>");
            MAP.put("module-empty.lore", List.of(
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>○ 空</dark_gray>",
                    "<gray>手持模块物品点击装备</gray>",
                    "<dark_gray>可用 /minion upgrade 获取</dark_gray>"
            ));
            MAP.put("collect.title", "<gold>收集全部</gold>");
            MAP.put("collect.lore", List.of(
                    "<gray>将仓库所有物品转移到背包</gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>当前仓存: <yellow>{count} 件</yellow></dark_gray>",
                    "<yellow>点击收集 ▶</yellow>"
            ));
            MAP.put("autosell-on.title", "<green>自动售卖</green>");
            MAP.put("autosell-on.lore", List.of(
                    "<dark_gray>状态: <green>● 开启</green></dark_gray>",
                    "<gray>仓库满时自动卖给商人</gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<yellow>点击关闭</yellow>"
            ));
            MAP.put("autosell-off.title", "<gray>自动售卖</gray>");
            MAP.put("autosell-off.lore", List.of(
                    "<dark_gray>状态: <red>○ 关闭</red></dark_gray>",
                    "<gray>仓库满时停止工作</gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<yellow>点击开启</yellow>"
            ));
            MAP.put("layout.title", "<aqua>理想布局</aqua>");
            MAP.put("layout.lore", List.of(
                    "<dark_gray>工作范围 <white>{side}x{side}</white></dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<green>{on}● 理想布局已开启（自动刷石中）</green>",
                    "<gray>{off}○ 点击开启理想布局（自动摆水/岩浆）</gray>",
                    "<gray>· 中心留空放置仆从</gray>",
                    "<gray>· 四周填充可采集方块</gray>",
                    "<gray>· 保证光照防刷怪</gray>",
                    "<dark_gray>· 多仆从共享边界可最大化产出</dark_gray>"
            ));
            MAP.put("pickup.title", "<red>拾取仆从</red>");
            MAP.put("pickup.lore", List.of(
                    "<gray>移除仆从并收回物品栏</gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>仓库、模块与皮肤会保留</dark_gray>",
                    "<dark_gray>潜行+右键仆从也可拾取</dark_gray>",
                    "<yellow>点击拾取 ▶</yellow>"
            ));
            MAP.put("close.title", "<red>关闭</red>");
            MAP.put("locked-slot.title", "<dark_gray>锁定槽位</dark_gray>");
            MAP.put("locked-slot.lore", List.of(
                    "<gray>升级仆从以解锁更多存储</gray>"
            ));

            // ---- 图鉴 GUI（/minions） ----
            MAP.put("collection-gui.title", "<aqua>仆从图鉴</aqua>");
            MAP.put("collection-gui.close.title", "<red>关闭</red>");
            MAP.put("collection-gui.filter.title", "<aqua>{sel}{name}</aqua>");
            MAP.put("collection-gui.card.title", "<gold>{name}</gold>");
            MAP.put("collection-gui.card.lore", List.of(
                    "<dark_gray>分类: <aqua>{category}</aqua></dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>已解锁等级 <yellow>{tier} / {max_tier}</yellow></dark_gray>",
                    "<dark_gray>已放置 <white>{placed} 个</white></dark_gray>",
                    "<dark_gray>总产出 <yellow>{produced} 件</yellow></dark_gray>",
                    "<dark_gray>收集 <white>{product}</white> <yellow>{collected}</yellow></dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<gray>下一级配方:</gray>",
                    "{r1}",
                    "{r2}",
                    "{r3}",
                    "<yellow>点击查看配方详情 ▶</yellow>"
            ));
            MAP.put("collection-gui.locked.title", "<dark_gray>???</dark_gray>");
            MAP.put("collection-gui.locked.lore", List.of(
                    "<dark_gray>分类: <aqua>{category}</aqua></dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<red>✖ 未解锁</red>",
                    "<dark_gray>收集 <white>{product}</white> <yellow>{collected} / {need}</yellow></dark_gray>"
            ));
            MAP.put("collection-gui.prev.title", "<green>◀ 上一页</green>");
            MAP.put("collection-gui.next.title", "<green>下一页 ▶</green>");
            MAP.put("collection-gui.progress.title", "<gold>图鉴进度 {unlocked}/{total}</gold>");
            MAP.put("collection-gui.progress.lore", List.of(
                    "<dark_gray>第 <white>{page} / {pages}</white> 页</dark_gray>",
                    "<gray>收集资源解锁更多仆从类型</gray>"
            ));
            MAP.put("collection-gui.recipe-header.title", "<gold>▼ {name} · 下一级配方</gold>");
            MAP.put("collection-gui.recipe-none.title", "<gray>尚未放置过该类型仆从，暂无升级记录</gray>");
            MAP.put("collection-gui.recipe-max.title", "<green>已达最高等级（{tier}）</green>");

            // ---- 燃料选择 GUI ----
            MAP.put("fuel-gui.title", "<gold>选择燃料</gold>");
            MAP.put("fuel-gui.empty.title", "<gray>背包中没有可用燃料</gray>");
            MAP.put("fuel-gui.option.title", "<yellow>{name}</yellow>");
            MAP.put("fuel-gui.option.lore", List.of(
                    "<dark_gray>加速 <green>+{boost}%</green></dark_gray>",
                    "<dark_gray>持续 <white>{duration}</white> / 个</dark_gray>",
                    "<dark_gray>背包数量 <yellow>{count}</yellow></dark_gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<yellow>点击安装全部 · 潜行安装 1 个</yellow>"
            ));
            MAP.put("fuel-gui.status.title", "<gray>当前燃料状态</gray>");
            MAP.put("fuel-gui.status.lore", List.of(
                    "<dark_gray>永久加速 <green>+{perm}%</green></dark_gray>",
                    "<dark_gray>限时剩余 <yellow>{left}</yellow></dark_gray>",
                    "<gray>{none}当前无燃料</gray>",
                    "<dark_gray>" + LINE + "</dark_gray>",
                    "<dark_gray>潜行点击卸下限时燃料</dark_gray>"
            ));
        }

        private Defaults() {
        }
    }
}
