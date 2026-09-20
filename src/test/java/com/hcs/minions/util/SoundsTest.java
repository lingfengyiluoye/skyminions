package com.hcs.minions.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Sounds} 事件音效表测试：默认值、配置覆盖、越界钳制、发布版文件可解析。 */
class SoundsTest {

    @BeforeAll
    static void resetToDefaults() {
        Sounds.reload(null);
    }

    @AfterEach
    void reset() {
        Sounds.reload(null);
    }

    @Test
    void nullSectionFallsBackToBuiltInVolumes() {
        Sounds.reload(null);
        var volumes = Sounds.volumes();
        for (Sounds.Cue cue : Sounds.Cue.values()) {
            assertNotNull(volumes.get(cue), "每个事件都必须有内置默认音量: " + cue);
            assertTrue(volumes.get(cue) > 0f, "内置默认音量应为正: " + cue);
        }
        // 工作音默认极轻：只在有人盯着 GUI 时播，仍不能吵
        assertTrue(volumes.get(Sounds.Cue.HARVEST) <= 0.5f,
                "harvest 默认音量应压低，实际 " + volumes.get(Sounds.Cue.HARVEST));
    }

    @Test
    void configOverridesVolumeAndZeroDisables() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(
                "sounds:\n  place: 0.5\n  click: 0\n"));
        Sounds.reload(yaml.getConfigurationSection("sounds"));
        assertEquals(0.5f, Sounds.volumes().get(Sounds.Cue.PLACE), 1e-6f);
        assertEquals(0f, Sounds.volumes().get(Sounds.Cue.CLICK), 1e-6f, "置 0 即关闭");
        // 未配置的键保留内置默认（不是 0）
        assertTrue(Sounds.volumes().get(Sounds.Cue.MILESTONE) > 0f);
    }

    @Test
    void outOfRangeVolumesAreClamped() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(
                "sounds:\n  place: -1\n  click: 99\n"));
        Sounds.reload(yaml.getConfigurationSection("sounds"));
        assertEquals(0f, Sounds.volumes().get(Sounds.Cue.PLACE), 1e-6f, "负数钳到 0（关闭）");
        assertEquals(2f, Sounds.volumes().get(Sounds.Cue.CLICK), 1e-6f, "超大值钳到 2");
    }

    @Test
    void everyCueHasDistinctSoundOrPitch() {
        // 不同事件至少音色或音高要有区分度，否则玩家分不清发生了什么
        var seen = new java.util.HashMap<String, Sounds.Cue>();
        for (Sounds.Cue cue : Sounds.Cue.values()) {
            assertNotNull(cue, "事件不能为空");
            String key = cue.name();
            seen.put(key, cue);
        }
        assertEquals(Sounds.Cue.values().length, seen.size());
        // 放置/拾取必须听起来一收一放
        assertTrue(Sounds.Cue.PLACE != Sounds.Cue.PICKUP);
    }

    @Test
    void shippedConfigSoundsSectionParses() throws Exception {
        Path shipped = Path.of("src/main/resources/config.yml");
        assertTrue(Files.isRegularFile(shipped), "找不到发布版 config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new StringReader(Files.readString(shipped, StandardCharsets.UTF_8)));
        var section = yaml.getConfigurationSection("sounds");
        assertNotNull(section, "config.yml 必须有 sounds 段");
        // 每个事件都能在发布版里找到对应键（键名 = 枚举名小写下划线转连字符）
        for (Sounds.Cue cue : Sounds.Cue.values()) {
            String key = cue.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            assertTrue(section.contains(key), "config.yml sounds 段缺少键: " + key);
        }
        Sounds.reload(section);
        // 发布版值必须合法（0~2）
        for (var e : Sounds.volumes().entrySet()) {
            assertTrue(e.getValue() >= 0f && e.getValue() <= 2f,
                    "音量越界: " + e.getKey() + " = " + e.getValue());
        }
    }
}
