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
            ON CONFLICT(id) DO UPDATE SET
              owner=excluded.owner, type=excluded.type, level=excluded.level, xp=excluded.xp,
              world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
              fuel_ticks=excluded.fuel_ticks, last_active=excluded.last_active,
              island_id=excluded.island_id, upgrade1=excluded.upgrade1, upgrade2=excluded.upgrade2,
              skin=excluded.skin, inventory=excluded.inventory
            """;

    private final File file;
    private final Object lock = new Object();
    private Connection connection;

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

    /** 幂等迁移：为旧库补充 upgrade1/upgrade2/skin 列（SQLite 不支持 ADD COLUMN IF NOT EXISTS）。 */
    private void migrate(Statement st) throws Exception {
        boolean hasUpgrade1 = false;
        boolean hasUpgrade2 = false;
        boolean hasSkin = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(minions)")) {
            while (rs.next()) {
                String col = rs.getString("name");
                if ("upgrade1".equalsIgnoreCase(col)) {
                    hasUpgrade1 = true;
                } else if ("upgrade2".equalsIgnoreCase(col)) {
                    hasUpgrade2 = true;
                } else if ("skin".equalsIgnoreCase(col)) {
                    hasSkin = true;
                }
            }
        }
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
        st.execute("CREATE INDEX IF NOT EXISTS idx_minions_owner ON minions(owner)");
    }

    @Override
    public void upsert(MinionData d) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
                bind(ps, d);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("SQLite upsert 失败", e);
            }
        }
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
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM minions WHERE id = ?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("SQLite delete 失败", e);
            }
        }
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
