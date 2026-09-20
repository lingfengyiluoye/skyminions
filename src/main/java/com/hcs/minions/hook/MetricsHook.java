package com.hcs.minions.hook;

import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * bStats 匿名用量统计（可选集成）。
 *
 * <p>只上报聚合计数（仆从数/类型数/存储后端），不含任何玩家或世界数据。
 * bStats 库由 paper-plugin.yml {@code libraries} 挂载；若类缺失则静默跳过。</p>
 */
public final class MetricsHook {

    private MetricsHook() {
    }

    /** 注册 bStats 图表（失败不影响插件启用）。 */
    public static void register(JavaPlugin plugin, java.util.function.IntSupplier minionCount,
                                java.util.function.IntSupplier typeCount,
                                java.util.function.Supplier<String> storageBackend) {
        try {
            Metrics metrics = new Metrics(plugin, 26000); // 插件 id 见 bStats 页面
            metrics.addCustomChart(new org.bstats.charts.SingleLineChart(
                    "placed_minions", minionCount::getAsInt));
            metrics.addCustomChart(new org.bstats.charts.SingleLineChart(
                    "minion_types", typeCount::getAsInt));
            metrics.addCustomChart(new org.bstats.charts.SimplePie(
                    "storage_backend", storageBackend::get));
        } catch (Throwable t) {
            // bStats 不可用（库未挂载/离线）时不应影响插件启用
            com.hcs.minions.util.Logs.debug("bStats 注册跳过: {}", t.getMessage());
        }
    }
}
