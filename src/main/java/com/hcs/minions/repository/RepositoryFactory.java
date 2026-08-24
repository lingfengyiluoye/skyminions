package com.hcs.minions.repository;

import com.hcs.minions.config.DatabaseConfig;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.repository.mysql.MysqlMinionStore;
import com.hcs.minions.repository.sqlite.SqliteMinionStore;
import com.hcs.minions.util.AsyncExecutor;

import java.io.File;

/**
 * 仓库工厂：按配置装配 SQLite / MySQL 底层存储，统一包一层带缓存的实现。
 * 业务层只拿到 {@link MinionRepository} 接口，对底层实现零感知。
 */
public final class RepositoryFactory {

    private RepositoryFactory() {
    }

    public static MinionRepository create(DatabaseConfig cfg, AsyncExecutor async, PluginConfig pluginConfig, File dataFolder) {
        MinionStore store;
        if (cfg.isSqlite()) {
            store = new SqliteMinionStore(new File(dataFolder, cfg.sqliteFile()));
        } else {
            store = new MysqlMinionStore(cfg);
        }
        store.init();
        return new CachedMinionRepository(store, async, pluginConfig);
    }
}
