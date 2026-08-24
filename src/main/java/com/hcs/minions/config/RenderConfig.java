package com.hcs.minions.config;

/**
 * Display Entity 渲染配置。
 *
 * @param viewRange 可视距离（用于限制每个仆从的渲染半径，防无限渲染）
 * @param scale     ItemDisplay 缩放
 */
public record RenderConfig(
        float viewRange,
        float scale
) {
}
