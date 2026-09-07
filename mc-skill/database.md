# Database Integration

## SQLite (Lightweight, Single-File)

Best for single-server plugins with moderate data.

```java
public class DatabaseManager {
    private final MyPlugin plugin;
    private Connection connection;

    public DatabaseManager(MyPlugin plugin) {
        this.plugin = plugin;
        connect();
    }

    private void connect() {
        File dbFile = new File(plugin.getDataFolder(), "database.db");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
        } catch (Exception e) {
            plugin.getLogger().severe("Database connection failed: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_data (
                    uuid VARCHAR(36) PRIMARY KEY,
                    data TEXT NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
        }
    }

    public CompletableFuture<Optional<String>> loadData(UUID uuid) {
        CompletableFuture<Optional<String>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT data FROM player_data WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                future.complete(rs.next() ? Optional.of(rs.getString("data")) : Optional.empty());
            } catch (SQLException e) {
                plugin.getLogger().severe("Load failed: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> saveData(UUID uuid, String data) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO player_data (uuid, data) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, data);
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().severe("Save failed: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void close() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().severe("Close failed: " + e.getMessage());
        }
    }
}
```

## MySQL with HikariCP (Production, Multi-Server)

Add HikariCP dependency:

```xml
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

```java
public class MySQLManager {
    private final MyPlugin plugin;
    private HikariDataSource dataSource;

    public MySQLManager(MyPlugin plugin, String host, int port, String db, String user, String pass) {
        this.plugin = plugin;
        connect(host, port, db, user, pass);
    }

    private void connect(String host, int port, String db, String user, String pass) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&characterEncoding=utf8mb4");
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setConnectionTimeout(10000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        dataSource = new HikariDataSource(config);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }
}
```

## Usage Pattern

```java
// Always use async for database operations
public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    db.loadData(player.getUniqueId()).thenAccept(data -> {
        // Back on async thread, switch to main for player ops
        Bukkit.getScheduler().runTask(plugin, () -> {
            data.ifPresent(d -> player.sendMessage(Component.text("Welcome back!")));
        });
    });
}

// Save on disconnect
public void onPlayerQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    String data = serializePlayerData(player);
    db.saveData(player.getUniqueId(), data);
}
```

## Best Practices

1. **Always async**: Never run DB queries on the main thread
2. **Connection pooling**: Use HikariCP for MySQL, not raw connections
3. **Prepared statements**: Always use `?` placeholders, never string concatenation
4. **Try-with-resources**: Close `PreparedStatement` and `ResultSet`
5. **Error handling**: Log errors, don't crash the server
6. **Graceful shutdown**: Close connections in `onDisable()`
7. **Data serialization**: Use JSON (Gson) for complex data structures
8. **Migrations**: Version your schema, support upgrades
