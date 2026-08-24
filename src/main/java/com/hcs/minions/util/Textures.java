package com.hcs.minions.util;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 头颅纹理辅助工具：从完整 base64 纹理值中提取皮肤 URL（现代 PlayerProfile API 的 setSkin 只接受 URL）。 */
public final class Textures {

    private Textures() {
    }

    /** 解码 base64 纹理 JSON 并提取 textures.SKIN.url；无效/缺失返回 null。 */
    public static URL skinUrl(String texture) {
        if (texture == null || texture.isEmpty()) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8);
            int i = json.indexOf("\"url\":\"");
            if (i < 0) {
                return null;
            }
            i += "\"url\":\"".length();
            int j = json.indexOf('"', i);
            if (j < 0) {
                return null;
            }
            return URI.create(json.substring(i, j)).toURL();
        } catch (Exception e) {
            return null;
        }
    }
}
