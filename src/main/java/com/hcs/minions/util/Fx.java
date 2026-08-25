package com.hcs.minions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * 统一玩家反馈出口（对齐 Hypixel 的提示节奏）：
 *
 * <ul>
 *   <li><b>ActionBar</b>：瞬时状态类反馈（加燃料/切换开关/装模块…），不刷聊天栏；</li>
 *   <li><b>Title</b>：高光时刻（升级成功/稀有掉落/里程碑），短驻留自动消失；</li>
 *   <li><b>Sound</b>：每次反馈伴随音效——成功叮声、拒绝低鸣、收集拾取音；</li>
 *   <li><b>Chat</b>：仅保留需要留档/多行的内容（清单、失败明细、里程碑详情）。</li>
 * </ul>
 *
 * <p>文本支持 MiniMessage 片段；所有出口均为静态工具方法。</p>
 */
public final class Fx {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Fx() {
    }

    /** 成功：ActionBar + 叮声（MiniMessage 文本）。 */
    public static void ok(Player player, String miniMessage) {
        ok(player, MM.deserialize(miniMessage));
    }

    /** 成功：ActionBar + 叮声（已构造组件）。 */
    public static void ok(Player player, Component component) {
        player.sendActionBar(component);
        sound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
    }

    /** 拒绝：ActionBar + 低鸣（MiniMessage 文本）。 */
    public static void deny(Player player, String miniMessage) {
        deny(player, MM.deserialize(miniMessage));
    }

    /** 拒绝：ActionBar + 低鸣（已构造组件）。 */
    public static void deny(Player player, Component component) {
        player.sendActionBar(component);
        sound(player, Sound.ENTITY_VILLAGER_NO, 0.9f);
    }

    /** 仅 ActionBar（不带音效）。 */
    public static void bar(Player player, String miniMessage) {
        player.sendActionBar(MM.deserialize(miniMessage));
    }

    /** 已构造好的组件直接上 ActionBar。 */
    public static void bar(Player player, Component component) {
        player.sendActionBar(component);
    }

    /** 收集/拾取类成功音。 */
    public static void pickup(Player player) {
        sound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
    }

    /** 高光时刻：Title（主+副）+ 升级音。 */
    public static void title(Player player, String mainMini, String subMini) {
        player.showTitle(Title.title(
                MM.deserialize(mainMini),
                MM.deserialize(subMini),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1400), Duration.ofMillis(200))));
        sound(player, Sound.ENTITY_PLAYER_LEVELUP, 1.0f);
    }

    /** 播放音效（空指针安全）。 */
    public static void sound(Player player, Sound sound, float pitch) {
        if (player == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, 0.6f, pitch);
    }
}
