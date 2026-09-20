package com.hcs.minions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信息卡/燃料卡的排版契约测试（不依赖 Bukkit 运行时，走 GuiText 内置默认模板）。
 *
 * <p>守住三条硬约束：</p>
 * <ul>
 *   <li><b>总行数预算</b>：含标题与唯一一条分隔线，全可选行拉满也要 ≤10 行
 *       （原版工具提示超过约 10~12 行会被屏幕上沿裁剪，状态行在首行最先被切）；</li>
 *   <li><b>无残留占位符</b>：渲染结果不得出现未注入的 {@code {xxx}}；</li>
 *   <li><b>状态在首行</b>：玩家最常找的信息排第一位。</li>
 * </ul>
 */
class CardLayoutContractTest {

    /** 全可选行都注入时的「最坏情况」变量表。 */
    private static Map<String, String> worstCaseInfoVars() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", "小麦仆从");
        v.put("tier", "XII");
        v.put("speed", "2");
        v.put("rate", "1800");
        v.put("range", "7x7");
        v.put("storage", "128/1792 件");
        v.put("storage_bar", "<green>▮▮▮▮▮</green><dark_gray>▯▯▯▯▯</dark_gray>");
        v.put("total", "12345");
        v.put("status", GuiText.raw("info.status-halted", Map.of()));
        v.put("halted_tip", GuiText.raw("info.halted-tip", Map.of()));
        v.put("next_pair", GuiText.raw("info.next-pair", Map.of("next", "3")));
        v.put("rare", "附魔腐肉");
        v.put("rare_chance", "2.50");
        v.put("next_tier", "VII");
        v.put("unlock_mats", "附魔小麦、金胡萝卜");
        v.put("hint", GuiText.raw("info.fuel-hint", Map.of()));
        return v;
    }

    @Test
    void infoCardWorstCaseStaysWithinLineBudget() {
        Map<String, String> v = worstCaseInfoVars();
        Component title = GuiText.title("info.title", v);
        List<Component> lore = GuiText.lore("info.lore", v);
        // 标题 1 行 + lore；分隔线只允许 1 条
        long dividers = lore.stream()
                .map(CardLayoutContractTest::plain)
                .filter(line -> line.contains("▬") || line.strip().isEmpty() && line.length() > 4)
                .count();
        assertTrue(lore.size() + 1 <= 10,
                "信息卡含标题总行数必须 ≤10（实际 " + (lore.size() + 1) + " 行）: " + dump(lore));
        assertTrue(dividers <= 1, "分隔线最多一条（实际 " + dividers + "）");
    }

    @Test
    void infoCardHasNoUnresolvedPlaceholders() {
        List<Component> lore = GuiText.lore("info.lore", worstCaseInfoVars());
        for (Component line : lore) {
            assertFalse(plain(line).contains("{"),
                    "存在未注入占位符: " + plain(line));
        }
    }

    @Test
    void infoCardStatusIsFirstLine() {
        List<Component> lore = GuiText.lore("info.lore", worstCaseInfoVars());
        String first = plain(lore.get(0));
        assertTrue(first.contains("仓库已满") || first.contains("运行中"),
                "首行必须是状态行，实际: " + first);
    }

    @Test
    void infoCardRunningCaseIsCompact() {
        // 运行中 + 无稀有 + 无解锁档 + 有燃料：应远低于预算
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", "圆石仆从");
        v.put("tier", "V");
        v.put("speed", "2");
        v.put("rate", "1800");
        v.put("range", "5x5");
        v.put("storage", "64/1792 件");
        v.put("storage_bar", "<green>▮</green><dark_gray>▯▯▯▯▯▯▯▯▯</dark_gray>");
        v.put("total", "999");
        v.put("status", GuiText.raw("info.status-working", Map.of()));
        v.put("next_pair", GuiText.raw("info.next-pair", Map.of("next", "3")));
        List<Component> lore = GuiText.lore("info.lore", v);
        assertTrue(lore.size() + 1 <= 8,
                "常见形态含标题应 ≤8 行（实际 " + (lore.size() + 1) + "）: " + dump(lore));
    }

    @Test
    void haltedCardStillShowsCumulativeWithoutNextCountdown() {
        // 停机：不显示「下次工作」倒计时，但「累计」行必须保留（曾因省略 next_pair
        // 导致整行被可选行机制一起隐藏——累计产出是最基础的信息）
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", "小麦仆从");
        v.put("tier", "XII");
        v.put("speed", "2");
        v.put("rate", "1800");
        v.put("range", "7x7");
        v.put("storage", "128/1792 件");
        v.put("storage_bar", "<red>▮▮▮▮▮▮▮▮▮▮</red>");
        v.put("total", "12345");
        v.put("status", GuiText.raw("info.status-halted", Map.of()));
        v.put("halted_tip", GuiText.raw("info.halted-tip", Map.of()));
        v.put("next_pair", ""); // 与 Minion.infoItem 停机分支一致：空串而非省略
        List<Component> lore = GuiText.lore("info.lore", v);
        String all = dump(lore);
        assertTrue(all.contains("12345"), "累计产出必须显示: " + all);
        assertFalse(all.contains("下次"), "停机时不应显示下次工作倒计时: " + all);
        assertTrue(all.contains("恢复工作"), "停机时应显示恢复提示: " + all);
    }

    @Test
    void fuelCardHasNoManualTextAndNoUnresolvedPlaceholders() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("left", GuiText.raw("fuel.left-line", Map.of(
                "boost", "25", "fraction", "45/64 分钟", "bar", "<green>▮▮▮▮▮▮▮</green><dark_gray>▯▯▯</dark_gray>")));
        v.put("perm", GuiText.raw("fuel.perm-line", Map.of("perm", "35")));
        v.put("mult", GuiText.raw("fuel.mult-line", Map.of(
                "mult", "×2.0", "fraction", "12/15 分钟", "bar", "<yellow>▮▮▮▮▮▮▮▮</yellow><dark_gray>▯▯</dark_gray>")));
        List<Component> lore = GuiText.lore("fuel.lore", v);
        String all = dump(lore);
        // 操作说明书已从卡片移除（完整指引在空手点击的聊天帮助里）
        assertFalse(all.contains("煤炭 / 岩浆桶"), "燃料卡不应常驻燃料清单: " + all);
        assertFalse(all.contains("右键小人"), "燃料卡不应常驻操作教学: " + all);
        for (Component line : lore) {
            assertFalse(plain(line).contains("{"), "存在未注入占位符: " + plain(line));
        }
        assertTrue(lore.size() + 1 <= 6, "燃料卡含标题应 ≤6 行（实际 " + (lore.size() + 1) + "）");
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static String dump(List<Component> lore) {
        StringBuilder sb = new StringBuilder();
        for (Component c : lore) {
            sb.append('\n').append(plain(c));
        }
        return sb.toString();
    }
}
