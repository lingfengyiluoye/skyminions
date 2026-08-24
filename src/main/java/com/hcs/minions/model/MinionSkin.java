package com.hcs.minions.model;

import com.hcs.minions.util.Logs;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 仆从皮肤（Hypixel 原版 Minion Skin 玩法）。
 *
 * <p>每个皮肤携带独立的头颅纹理 base64（形如 minecraft-heads.com / mcheads.ru 提供的
 * Value 字段），用于渲染小人头盔。纹理值可在 config.yml 的 {@code skins} 段覆盖，
 * 方便服主自行替换为任意头颅库的真实贴图。</p>
 *
 * <p>注意：内置默认值均经过 Mojang 材质 CDN 可达性验证；无效/失效的 base64 会导致
 * 客户端回退显示原版 Steve 头。</p>
 */
public enum MinionSkin {

    // 内置纹理为已验证可用的头颅贴图 base64（金块头 / 钻石块头，来源 mcheads 社区数据）；
    // DEFAULT 与 HALLOWEEN 的社区数据已失效，默认回退全局 head-texture，建议服主在 config.yml 覆盖。
    DEFAULT("default", "默认皮肤", null),
    GOLDEN("golden", "黄金皮肤",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5jcmFmdC5uZXQvdGV4dHVyZS85N2Y1N2U3YWE4ZGU4NjU5MWJiMGJjNTJjYmEzMGE0OWQ5MzFiZmFiYmQ0N2JiYzgwYmRkNjYyMjUxMzkyMTYxIn19fQ=="),
    DIAMOND("diamond", "钻石皮肤",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5jcmFmdC5uZXQvdGV4dHVyZS85NjMxNTk3ZGNlNGU0MDUxZThkNWE1NDM2NDE5NjZhYjU0ZmJmMjVhMGVkNjA0N2YxMWU2MTRkODhiZjQ4ZiJ9fX0="),
    HALLOWEEN("halloween", "万圣节皮肤", null);

    private final String key;
    private final String displayName;
    private final String builtinTexture;

    /** 运行时纹理覆盖（config.yml skins 段，/minion reload 刷新）。 */
    private static final Map<String, String> OVERRIDES = new ConcurrentHashMap<>();

    MinionSkin(String key, String displayName, String builtinTexture) {
        this.key = key;
        this.displayName = displayName;
        this.builtinTexture = builtinTexture;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    /** 皮肤纹理 base64；null/空串表示回退到全局默认贴图（head-texture）。 */
    public String texture() {
        String override = OVERRIDES.get(key);
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return builtinTexture;
    }

    public static Optional<MinionSkin> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(s -> s.key.equalsIgnoreCase(key)).findFirst();
    }

    /**
     * 从 config.yml 的 skins 段加载纹理覆盖（键 = 皮肤 key，值 = base64 纹理）。
     * 由 ConfigProvider.reload 链路调用，支持热重载。
     */
    public static void loadTextures(ConfigurationSection yaml) {
        OVERRIDES.clear();
        ConfigurationSection section = yaml.getConfigurationSection("skins");
        if (section == null) {
            return;
        }
        for (String k : section.getKeys(false)) {
            String texture = section.getString(k, "");
            if (fromKey(k).isEmpty()) {
                Logs.warn("skins 配置含未知皮肤键: {}（可用: {}）", k,
                        String.join(", ", Arrays.stream(values()).map(MinionSkin::key).toList()));
                continue;
            }
            if (texture != null && !texture.isBlank()) {
                OVERRIDES.put(k.toLowerCase(Locale.ROOT), texture.trim());
            }
        }
    }
}
