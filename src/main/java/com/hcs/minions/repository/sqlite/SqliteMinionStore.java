package com.hcs.minions.repository.sqlite;

import com.hcs.minions.model.MinionData;
import com.hcs.minions.repository.MinionStore;
import com.hcs.minions.util.Logs;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite 实现。SQLite 写入串行，单连接 + 锁即可；调用方（CachedMinionRepository）
 * 已通过虚拟线程隔离 IO，这里只负责阻塞 JDBC。
 */
public final class SqliteMinionStore implements MinionStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS minions (
              id VARCHAR(36) PRIMARY KEY,
              owner VARCHAR(36) NOT NULL,
              type VARCHAR(32) NOT NULL,
              level INT NOT NULL,
              xp BIGINT NOT NULL,
              world VARCHAR(64) NOT NULL,
              x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
              fuel_ticks BIGINT NOT NULL,
              fuel_boost DOUBLE NOT NULL DEFAULT 1.0,
              mult_boost DOUBLE NOT NULL DEFAULT 1.0,
              mult_ticks BIGINT NOT NULL DEFAULT 0,
              last_active BIGINT NOT NULL,
              island_id VARCHAR(36),
              upgrade1 VARCHAR(32),
              upgrade2 VARCHAR(32),
              upgrade3 VARCHAR(32),
              upgrade4 VARCHAR(32),
              skin VARCHAR(32),
              auto_sell INT NOT NULL DEFAULT 0,
              total_produced BIGINT NOT NULL DEFAULT 0,
              permanent_boost DOUBLE NOT NULL DEFAULT 1.0,
              inventory BLOB
            )
            """;

    private static final String UPSERT = """
            INSERT INTO minions (id, owner, type, level, xp, world, x, y, z, fuel_ticks, fuel_boost, mult_boost, mult_ticks, last_active, island_id, upgrade1, upgrade2, upgrade3, upgrade4, skin, auto_sell, total_produced, permanent_boost, inventory)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
              owner=excluded.owner, type=excluded.type, level=excluded.level, xp=excluded.xp,
              world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
              fuel_ticks=excluded.fuel_ticks, fuel_boost=excluded.fuel_boost,
              mult_boost=excluded.mult_boost, mult_ticks=excluded.mult_ticks,
              last_active=excluded.last_active,
              island_id=excluded.island_id, upgrade1=excluded.upgrade1, upgrade2=excluded.upgrade2,
              upgrade3=excluded.upgrade3, upgrade4=excluded.upgrade4,
              skin=excluded.skin, auto_sell=excluded.auto_sell, total_produced=excluded.total_produced,
              permanent_boost=excluded.permanent_boost, inventory=excluded.inventory
            """;

    private final File file;
    private final Object lock = new Object();
    private Connection connection;

    /** 写操作重试策略：3 次尝试，退避 50ms/200ms（抖动场景下避免单次失败直接丢数据）。 */
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] RETRY_DELAY_MS = {50, 200};

    public SqliteMinionStore(File file) {
        this.file = file;
    }

    @Override
    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute(DDL);
                migrate(st);
            }
        } catch (Exception e) {
            Logs.error("SQLite 初始化失败", e);
            throw new IllegalStateException("无法初始化 SQLite", e);
        }
    }

    /** 幂等迁移：为旧库补充 upgrade1/upgrade2/skin/auto_sell/total_produced/permanent_boost 列（SQLite 不支持 ADD COLUMN IF NOT EXISTS）。 */
    private void migrate(Statement st) throws Exception {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(minions)")) {
            while (rs.next()) {
                existing.add(rs.getString("name").toLowerCase());
            }
        }
        addColumnIfMissing(st, existing, "upgrade1", "VARCHAR(32)", null);
        addColumnIfMissing(st, existing, "upgrade2", "VARCHAR(32)", null);
        addColumnIfMissing(st, existing, "upgrade3", "VARCHAR(32)", null);
        addColumnIfMissing(st, existing, "upgrade4", "VARCHAR(32)", null);
        addColumnIfMissing(st, existing, "skin", "VARCHAR(32)", null);
        addColumnIfMissing(st, existing, "fuel_boost", "DOUBLE NOT NULL", "1.0");
        addColumnIfMissing(st, existing, "mult_boost", "DOUBLE NOT NULL", "1.0");
        addColumnIfMissing(st, existing, "mult_ticks", "BIGINT NOT NULL", "0");
        addColumnIfMissing(st, existing, "auto_sell", "INT NOT NULL", "0");
        addColumnIfMissing(st, existing, "total_produced", "BIGINT NOT NULL", "0");
        addColumnIfMissing(st, existing, "permanent_boost", "DOUBLE NOT NULL", "1.0");
        // owner 索引（按主人查询/统计时避免全表扫）
        st.execute("CREATE INDEX IF NOT EXISTS idx_minions_owner ON minions(owner)");
    }

    private static void addColumnIfMissing(Statement st, java.util.Set<String> existing,
                                            String column, String type, String defaultValue) throws Exception {
        if (existing.contains(column.toLowerCase())) {
            return;
        }
        String ddl = "ALTER TABLE minions ADD COLUMN " + column + " " + type;
        if (defaultValue != null) {
            ddl += " DEFAULT " + defaultValue;
        }
        st.execute(ddl);
    }

    @Override
    public void upsert(MinionData d) {
        writeWithRetry("upsert", () -> {
            try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
                bind(ps, d);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public Optional<MinionData> select(UUID id) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM minions WHERE id = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            } catch (Exception e) {
                throw new RuntimeException("SQLite select 失败", e);
            }
        }
    }

    @Override
    public List<MinionData> selectAll() {
        synchronized (lock) {
            List<MinionData> out = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM minions")) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            } catch (Exception e) {
                throw new RuntimeException("SQLite selectAll 失败", e);
            }
            return out;
        }
    }

    @Override
    public void delete(UUID id) {
        writeWithRetry("delete", () -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM minions WHERE id = ?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
        });
    }

    /** 写操作带指数退避重试：重试耗尽后抛出（上层 CachedMinionRepository 会保留脏标记下轮再试）。 */
    private void writeWithRetry(String op, SqlAction action) {
        synchronized (lock) {
            for (int attempt = 1; ; attempt++) {
                try {
                    action.run();
                    return;
                } catch (Exception e) {
                    if (attempt >= MAX_ATTEMPTS) {
                        throw new RuntimeException("SQLite " + op + " 失败（已重试 " + MAX_ATTEMPTS + " 次）", e);
                    }
                    Logs.warn("SQLite {} 第 {} 次失败，{}ms 后重试", op, attempt, RETRY_DELAY_MS[attempt - 1]);
                    try {
                        Thread.sleep(RETRY_DELAY_MS[attempt - 1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("SQLite " + op + " 重试被中断", ie);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    Logs.error("SQLite 连接关闭失败", e);
                }
            }
        }
    }

    private static void bind(PreparedStatement ps, MinionData d) throws Exception {
        int i = 1;
        ps.setString(i++, d.id().toString());
        ps.setString(i++, d.owner().toString());
        ps.setString(i++, d.type());
        ps.setInt(i++, d.level());
        ps.setLong(i++, 0L); // xp 列保留但业务层已不使用（旧版字段，恒 0）
        ps.setString(i++, d.world());
        ps.setInt(i++, d.x());
        ps.setInt(i++, d.y());
        ps.setInt(i++, d.z());
        ps.setLong(i++, d.fuelTicks());
        ps.setDouble(i++, d.fuelBoost());
        ps.setDouble(i++, d.multBoost());
        ps.setLong(i++, d.multTicks());
        ps.setLong(i++, d.lastActiveEpochMs());
        ps.setString(i++, d.islandId());
        ps.setString(i++, d.upgrade1());
        ps.setString(i++, d.upgrade2());
        ps.setString(i++, d.upgrade3());
        ps.setString(i++, d.upgrade4());
        ps.setString(i++, d.skin());
        ps.setInt(i++, d.autoSell() ? 1 : 0);
        ps.setLong(i++, d.totalProduced());
        ps.setDouble(i++, d.permanentBoost());
        ps.setBytes(i, d.inventory());
    }

    private static MinionData map(ResultSet rs) throws Exception {
        return new MinionData(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("owner")),
                rs.getString("type"),
                rs.getInt("level"),
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getLong("fuel_ticks"),
                rs.getDouble("fuel_boost"),
                rs.getDouble("mult_boost"),
                rs.getLong("mult_ticks"),
                rs.getLong("last_active"),
                rs.getString("island_id"),
                rs.getString("upgrade1"),
                rs.getString("upgrade2"),
                rs.getString("upgrade3"),
                rs.getString("upgrade4"),
                rs.getString("skin"),
                rs.getInt("auto_sell") != 0,
                rs.getLong("total_produced"),
                rs.getDouble("permanent_boost"),
                rs.getBytes("inventory")
        );
    }
}
