package com.hcs.minions.util;

/**
 * 字符画进度条（Hypixel 风格仪表盘可视化）。
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li><b>同维度</b>：current 与 max 必须是同一单位的量（件/件、格/格、秒/秒）。
 *       把「128 件」和「28 格」写成 128/28 是没有意义的分数，调用方须先换算；</li>
 *   <li><b>颜色纪律</b>：全站语义色只有四种——红=异常、黄=注意/累计、绿=正常/正向、
 *       白=中性数值。进度条按占用率取色（&lt;70% 绿 / 70~95% 黄 / ≥95% 红），
 *       空格段用深灰，不引入额外色相；</li>
 *   <li><b>超出钳制</b>：current &gt; max 时条满格不溢出，分数仍显示真实值。</li>
 * </ul>
 *
 * <p>纯函数、无 Bukkit 依赖，任意线程可调。</p>
 */
public final class Bars {

    /** 默认段数（10 段：一眼可读又不占宽度）。 */
    public static final int SEGMENTS = 10;

    /** 变黄阈值（占用率）。 */
    private static final double WARN_RATIO = 0.70;
    /** 变红阈值（占用率）。 */
    private static final double FULL_RATIO = 0.95;

    private static final String FILLED = "▮";
    private static final String EMPTY = "▯";

    private Bars() {
    }

    /** 裸进度条（无颜色标签），段数见 {@link #SEGMENTS}。 */
    public static String of(long current, long max) {
        return of(current, max, SEGMENTS);
    }

    /** 裸进度条（指定段数）；max≤0 时返回全空条。 */
    public static String of(long current, long max, int segments) {
        int filled = filledCount(current, max, segments);
        return FILLED.repeat(filled) + EMPTY.repeat(Math.max(0, segments - filled));
    }

    /**
     * 带语义色的进度条（MiniMessage 片段，可直接作为占位符值注入模板）。
     * 已占用段按 {@link #WARN_RATIO}/{@link #FULL_RATIO} 取绿/黄/红，未占用段深灰。
     */
    public static String colored(long current, long max) {
        return colored(current, max, SEGMENTS);
    }

    /** 带语义色的进度条（指定段数）。 */
    public static String colored(long current, long max, int segments) {
        int filled = filledCount(current, max, segments);
        StringBuilder sb = new StringBuilder();
        if (filled > 0) {
            String color = colorOf(current, max);
            String name = color.substring(1, color.length() - 1); // "<green>" -> "green"
            sb.append(color).append(FILLED.repeat(filled)).append("</").append(name).append('>');
        }
        if (filled < segments) {
            sb.append("<dark_gray>").append(EMPTY.repeat(segments - filled)).append("</dark_gray>");
        }
        return sb.toString();
    }

    /** 占用率对应的语义色标签（绿/黄/红）。 */
    public static String colorOf(long current, long max) {
        if (max <= 0) {
            return "<red>";
        }
        double ratio = (double) current / max;
        if (ratio >= FULL_RATIO) {
            return "<red>";
        }
        return ratio >= WARN_RATIO ? "<yellow>" : "<green>";
    }

    /**
     * 同维度分数（自动选单位）：tick 计时按「分钟/秒」折算，其余按原单位。
     * 例：{@code fraction(54000, 76800)} → {@code "45/64 分钟"}。
     */
    public static String fractionTicks(long currentTicks, long maxTicks) {
        long maxMinutes = Math.max(0L, maxTicks) / 20 / 60;
        if (maxMinutes >= 1) {
            long curMinutes = Math.max(0L, currentTicks) / 20 / 60;
            return curMinutes + "/" + maxMinutes + " 分钟";
        }
        return (Math.max(0L, currentTicks) / 20) + "/" + (Math.max(0L, maxTicks) / 20) + " 秒";
    }

    /** 同维度分数（调用方自带单位文案）：{@code "128/1792 件"}。 */
    public static String fraction(long current, long max, String unit) {
        return Math.max(0L, current) + "/" + Math.max(0L, max) + " " + unit;
    }

    private static int filledCount(long current, long max, int segments) {
        if (max <= 0 || segments <= 0) {
            return 0;
        }
        double ratio = (double) Math.max(0L, current) / max;
        if (ratio >= 1.0) {
            return segments;
        }
        return (int) Math.round(ratio * segments);
    }
}
