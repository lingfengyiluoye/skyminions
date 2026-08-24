package com.hcs.minions.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 仆从皮肤（Hypixel 原版 Minion Skin 玩法）。
 *
 * <p>每个皮肤携带独立的头颅纹理 base64，用于渲染小人头盔。
 * 默认皮肤为 {@link #DEFAULT}，其余为可选的稀有皮肤。</p>
 */
public enum MinionSkin {

    // 各皮肤纹理为 Minecraft 头颅贴图的 base64 值（离线模式可用）
    DEFAULT("default", "默认皮肤",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjYzNDRmYzVhOTlkYTQ3YjMyZDlkODljNTBmZWRjYTlkYmE5MzA1MDE2OTY1ZGE0YWEwOTM4MTc3YzU5N2M5ZiJ9fX0="),
    GOLDEN("golden", "黄金皮肤",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDI0YjEyNmQ5ODNiYjdiM2Q5YzE5MjI2ZmI3NTBjMGEzODI0ZGI2MjBhNmQ1YzU0Zjc4ZGYxN2ZmM2VjM2I5ZiJ9fX0="),
    DIAMOND("diamond", "钻石皮肤",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDk0YjU2NjY0YzI1YmQ0MjViOWE0YjI5YmRjZjM4M2RhMTQxMTY5M2M5NDg0NWM0ZDJmNmRhODdjYzdlODUxMSJ9fX0="),
    HALLOWEEN("halloween", "万圣节皮肤",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2ZhZjg5OTUzZjE3YzE4ZDU5NjU3MjdlNzE1MjVmYjE4MmE3MjQ4YzU2MjJjNGEyYTFiYjc0MTVmOTZiNTRmZCJ9fX0=");

    private final String key;
    private final String displayName;
    private final String texture;

    MinionSkin(String key, String displayName, String texture) {
        this.key = key;
        this.displayName = displayName;
        this.texture = texture;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    /** 皮肤纹理 base64；空串表示回退到全局默认贴图。 */
    public String texture() {
        return texture;
    }

    public static Optional<MinionSkin> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(s -> s.key.equalsIgnoreCase(key)).findFirst();
    }
}
