package com.hcs.minions.config;

import org.bukkit.Material;

import java.util.Locale;

/**
 * 附魔资源定义（config.yml {@code enchanted-resources:} 段每项）。
 *
 * <pre>
 * enchanted-resources:
 *   - key: coal           # 唯一 key（配方用 enchanted:coal 引用）
 *     name: 附魔煤炭       # 中文显示名
 *     base: COAL          # 基底原版物料
 *     ratio: 160          # 压缩比（1 个附魔资源 = ratio 个基底）
 * </pre>
 *
 * @param key   唯一 key（小写）
 * @param name  中文显示名
 * @param base  基底原版物料
 * @param ratio 压缩比（≥1）
 */
public record EnchantedResourceDef(String key, String name, Material base, int ratio) {

    public EnchantedResourceDef {
        key = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? key : name.trim();
        ratio = Math.max(1, ratio);
    }

    /** 解析单条配置；非法项告警并返回 null。 */
    public static EnchantedResourceDef parse(Object raw) {
        if (!(raw instanceof java.util.Map<?, ?> m)) {
            return null;
        }
        Object keyRaw = m.get("key");
        if (keyRaw == null || String.valueOf(keyRaw).isBlank()) {
            com.hcs.minions.util.Logs.warn("enchanted-resources 配置项缺少 key，已跳过: {}", m);
            return null;
        }
        String key = String.valueOf(keyRaw).trim().toLowerCase(Locale.ROOT);
        Object baseRaw = m.get("base");
        Material base = null;
        if (baseRaw != null) {
            try {
                base = Material.valueOf(String.valueOf(baseRaw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                com.hcs.minions.util.Logs.warn("enchanted-resources {} 的 base 无法识别: {}", key, baseRaw);
                return null;
            }
        }
        if (base == null) {
            com.hcs.minions.util.Logs.warn("enchanted-resources {} 缺少 base，已跳过", key);
            return null;
        }
        int ratio = m.get("ratio") instanceof Number n ? Math.max(1, n.intValue()) : 160;
        return new EnchantedResourceDef(key, m.get("name") == null ? key : String.valueOf(m.get("name")), base, ratio);
    }
}
