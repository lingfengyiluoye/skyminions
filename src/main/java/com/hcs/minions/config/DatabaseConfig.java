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

    public DatabaseConfig {
        type = type == null || type.isBlank() ? "sqlite" : type.trim().toLowerCase(java.util.Locale.ROOT);
        sqliteFile = sqliteFile == null || sqliteFile.isBlank() ? "minions.db" : sqliteFile;
        host = host == null || host.isBlank() ? "127.0.0.1" : host;
        port = port < 1 || port > 65535 ? 3306 : port;
        database = database == null || database.isBlank() ? "minions" : database;
        user = user == null ? "" : user;
        password = password == null ? "" : password;
        poolSize = Math.max(2, poolSize);
    }

    public boolean isSqlite() {
        return "sqlite".equalsIgnoreCase(type);
    }
}
