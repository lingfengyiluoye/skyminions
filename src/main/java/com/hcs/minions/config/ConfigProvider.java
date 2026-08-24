package com.hcs.minions.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置热重载容器：持有当前生效的 {@link PluginConfig} 不可变快照。
 *
 * <p>业务服务通过 {@link #get()} 读取，{@link #reload()} 原子替换快照，
 * 使 {@code /minion reload} 无需重启即可更新运行时参数（效率/价格/周期等）。</p>
 */
public final class ConfigProvider {

    private final JavaPlugin plugin;
    private final AtomicReference<PluginConfig> ref;

    public ConfigProvider(JavaPlugin plugin, PluginConfig initial) {
        this.plugin = plugin;
        this.ref = new AtomicReference<>(initial);
    }

    public PluginConfig get() {
        return ref.get();
    }

    /** 重新加载 config.yml 并原子替换快照；返回新快照。 */
    public PluginConfig reload() {
        PluginConfig next = ConfigLoader.load(plugin);
        ref.set(next);
        return next;
    }
}
