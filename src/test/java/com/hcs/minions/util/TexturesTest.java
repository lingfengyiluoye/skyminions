package com.hcs.minions.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** {@link Textures} 皮肤 URL 提取测试（纯函数，无 Bukkit 依赖）。 */
class TexturesTest {

    /** 构造含 textures.SKIN.url 的标准 base64 纹理值。 */
    private static String textureWithUrl(String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void validTextureExtractsUrl() throws Exception {
        String url = "http://textures.minecraft.net/texture/abc123";
        java.net.URL parsed = Textures.skinUrl(textureWithUrl(url));
        assertNotNull(parsed);
        org.junit.jupiter.api.Assertions.assertTrue(
                parsed.toString().contains("abc123"), "应提取到原始 URL: " + parsed);
    }

    @Test
    void invalidTextureReturnsNull() {
        assertNull(Textures.skinUrl(null));
        assertNull(Textures.skinUrl(""));
        assertNull(Textures.skinUrl("not-base64!!!"));
        // 合法 base64 但不是皮肤 JSON
        String wrongJson = java.util.Base64.getEncoder()
                .encodeToString("{\"foo\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(Textures.skinUrl(wrongJson));
    }
}
