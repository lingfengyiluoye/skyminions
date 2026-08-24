package com.hcs.minions.config;

/**
 * 数据库配置对象（强类型，仅被数据访问层读取）。
 */
public record DatabaseConfig(
        String type,
        String sqliteFile,
        String host,
        int port,
        String database,
        String user,
        String password,
        int poolSize
) {

    public boolean isSqlite() {
        return "sqlite".equalsIgnoreCase(type);
    }
}
