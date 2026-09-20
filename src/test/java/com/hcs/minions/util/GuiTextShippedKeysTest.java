package com.hcs.minions.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布版 gui.yml 文案键回归测试：代码里所有 {@code GuiText.title/raw} 依赖的键
 * 都必须能在发布版 gui.yml 中找到（否则服主改了文件/键名打错时静默回退默认，
 * 「可定制」承诺失效）。走 Reader 路径（File 变体会请求 Bukkit.getLogger()）。
 */
class GuiTextShippedKeysTest {

    /** 代码依赖、且必须可在 gui.yml 中定制的文案键（不含 GuiText.Defaults 兜底的全部键）。 */
    private static final List<String> REQUIRED_KEYS = List.of(
            // 行内嵌行片段（GuiText.raw）
            "upgrade.material-line", "upgrade.body-line", "upgrade.more-line",
            "craft-gui.material-line", "craft-gui.body-line", "craft-gui.more-line",
            "collection-gui.card.recipe-line", "collection-gui.card.body-line",
            "collection-gui.card.more-line",
            "collection-gui.chat.recipe-line", "collection-gui.chat.body-line",
            // 状态行 / 高光 Title / 物品自身文案 / 指南聊天行
            "info.status-halted", "info.status-working",
            "craft-gui.success.title", "craft-gui.success.subtitle",
            "rare-drop.title", "rare-drop.subtitle",
            "enchanted.title", "enchanted.lore",
            "module-item.title", "module-item.lore",
            "minion-item.title", "minion-item.lore",
            "guide.chat-head", "guide.chat-source", "guide.chat-source-fallback"
    );

    @Test
    void shippedGuiYmlContainsAllCodeDependentKeys() throws Exception {
        Path shipped = Path.of("src/main/resources/gui.yml");
        assertTrue(Files.isRegularFile(shipped), "找不到发布版 gui.yml");
        String content = Files.readString(shipped, StandardCharsets.UTF_8);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(content));
        for (String key : REQUIRED_KEYS) {
            assertTrue(yaml.contains(key),
                    "gui.yml 缺少文案键 " + key + "（代码已改为模板驱动，缺键将静默回退默认）");
        }
        // 文件本身含 layout 段（由 GuiLayout 解析）；GuiText.load 负责把它从文案表中剔除，
        // 该过滤逻辑由 GuiLayoutTest/GuiText 的 load 路径覆盖，此处只验文案键齐全
        assertTrue(yaml.contains("layout.storage.slots"), "gui.yml 应保留 layout 段");
    }
}
