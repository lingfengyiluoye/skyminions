package com.hcs.minions.repository.mysql;

import com.hcs.minions.config.DatabaseConfig;
import com.hcs.minions.model.MinionData;
import com.hcs.minions.repository.MinionStore;
import com.hcs.minions.util.Logs;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL 实现，基于 HikariCP。
 *
 * <p>虚拟线程 + JDBC：mysql-connector-j 的阻塞调用会让虚拟线程“固定”其载体线程，
 * 因此连接池保持小规模（默认 4），绝不按仆从数量放大；连接超时收紧以避免堆积。
 */
public final class MysqlMinionStore implements MinionStore {

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
              last_active BIGINT NOT NULL,
              island_id VARCHAR(36),
              upgrade1 VARCHAR(32),
              upgrade2 VARCHAR(32),
              skin VARCHAR(32),
              inventory BLOB
            )
            """;

    private static final String UPSERT = """
            INSERT INTO minions (id, owner, type, level, xp, world, x, y, z, fuel_ticks, last_active, island_id, upgrade1, upgrade2, skin, inventory)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              owner=VALUES(owner), type=VALUES(type), level=VALUES(level), xp=VALUES(xp),
              world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z),
              fuel_ticks=VALUES(fuel_ticks), last_active=VALUES(last_active),
              island_id=VALUES(island_id), upgrade1=VALUES(upgrade1), upgrade2=VALUES(upgrade2),
              skin=VALUES(skin), inventory=VALUES(inventory)
            """;

    private final DatabaseConfig config;
    private HikariDataSource dataSource;

    public MysqlMinionStore(DatabaseConfig config) {
        this.config = config;
    }

    @Override
    public void init() {
        // shade 时 mysql/sqlite 的 META-INF/services/java.sql.Driver 会相互覆盖，
        // 显式加载驱动类，确保 DriverManager 能识别 MySQL 驱动。
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("未找到 MySQL JDBC 驱动", e);
        }
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.database()
                + "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8");
        hc.setUsername(config.user());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(Math.max(2, config.poolSize()));
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(5_000);
        hc.setPoolName("hcs-minions-mysql");
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "128");
        this.dataSource = new HikariDataSource(hc);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(DDL);
            migrate(c);
        } catch (Exception e) {
            Logs.error("MySQL 初始化失败", e);
            throw new IllegalStateException("无法初始化 MySQL", e);
        }
    }

    /** 幂等迁移：为旧库补充 upgrade1/upgrade2/skin 列（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）。 */
    private void migrate(Connection c) throws Exception {
        boolean hasUpgrade1 = false;
        boolean hasUpgrade2 = false;
        boolean hasSkin = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'minions'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if ("upgrade1".equalsIgnoreCase(col)) {
                        hasUpgrade1 = true;
                    } else if ("upgrade2".equalsIgnoreCase(col)) {
                        hasUpgrade2 = true;
                    } else if ("skin".equalsIgnoreCase(col)) {
                        hasSkin = true;
                    }
                }
            }
        }
        try (Statement st = c.createStatement()) {
            if (!hasUpgrade1) {
                st.execute("ALTER TABLE minions ADD COLUMN upgrade1 VARCHAR(32)");
            }
            if (!hasUpgrade2) {
                st.execute("ALTER TABLE minions ADD COLUMN upgrade2 VARCHAR(32)");
            }
            if (!hasSkin) {
                st.execute("ALTER TABLE minions ADD COLUMN skin VARCHAR(32)");
            }
            // owner 索引（按主人查询/统计时避免全表扫）
            if (!indexExists(c, "idx_minions_owner")) {
                st.execute("CREATE INDEX idx_minions_owner ON minions(owner)");
            }
        }
    }

    private boolean indexExists(Connection c, String indexName) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'minions' AND INDEX_NAME = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    @Override
    public void upsert(MinionData d) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPSERT)) {
            bind(ps, d);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("MySQL upsert 失败", e);
        }
    }

    @Override
    public Optional<MinionData> select(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM minions WHERE id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("MySQL select 失败", e);
        }
    }

    @Override
    public List<MinionData> selectAll() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM minions")) {
            List<MinionData> out = new ArrayList<>();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("MySQL selectAll 失败", e);
        }
    }

    @Override
    public void delete(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM minions WHERE id = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("MySQL delete 失败", e);
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static void bind(PreparedStatement ps, MinionData d) throws Exception {
        int i = 1;
        ps.setString(i++, d.id().toString());
        ps.setString(i++, d.owner().toString());
        ps.setString(i++, d.type());
        ps.setInt(i++, d.level());
        ps.setLong(i++, d.xp());
        ps.setString(i++, d.world());
        ps.setInt(i++, d.x());
        ps.setInt(i++, d.y());
        ps.setInt(i++, d.z());
        ps.setLong(i++, d.fuelTicks());
        ps.setLong(i++, d.lastActiveEpochMs());
        ps.setString(i++, d.islandId());
        ps.setString(i++, d.upgrade1());
        ps.setString(i++, d.upgrade2());
        ps.setString(i++, d.skin());
        ps.setBytes(i, d.inventory());
    }

    private static MinionData map(ResultSet rs) throws Exception {
        return new MinionData(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("owner")),
                rs.getString("type"),
                rs.getInt("level"),
                rs.getLong("xp"),
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getLong("fuel_ticks"),
                rs.getLong("last_active"),
                rs.getString("island_id"),
                rs.getString("upgrade1"),
                rs.getString("upgrade2"),
                rs.getString("skin"),
                rs.getBytes("inventory")
        );
    }
}
