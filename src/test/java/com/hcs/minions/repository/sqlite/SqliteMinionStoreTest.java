package com.hcs.minions.repository.sqlite;

import com.hcs.minions.model.MinionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 存储层集成测试（真实 JDBC，内存外临时库文件）。
 * 覆盖：字段往返（含 fuel_boost）、null 列处理、删除语义、
 * 以及「旧版表结构自动迁移」——与线上旧库升级路径一致。
 */
class SqliteMinionStoreTest {

    private static MinionData sample(UUID id) {
        return new MinionData(
                id, UUID.randomUUID(), "cobble", 5,
                "world", 600, 100, 2,
                72000L, 1.25, 2.0, 18000L, System.currentTimeMillis(), "island-abc",
                "auto_smelter", null, null, null, "golden",
                true, 12345L, 1.30,
                new byte[]{1, 2, 3, 4}
        );
    }

    private static final String LEGACY_DDL = """
            CREATE TABLE minions (
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
              inventory BLOB
            )
            """;

    @Test
    void upsertThenSelectRoundTripsAllFields(@TempDir Path dir) {
        SqliteMinionStore store = new SqliteMinionStore(dir.resolve("t1.db").toFile());
        store.init();
        try {
            UUID id = UUID.randomUUID();
            MinionData in = sample(id);
            store.upsert(in);

            Optional<MinionData> outOpt = store.select(id);
            assertTrue(outOpt.isPresent(), "upsert 后必须能查到");
            MinionData out = outOpt.get();
            assertEquals(in.id(), out.id());
            assertEquals(in.owner(), out.owner());
            assertEquals("cobble", out.type());
            assertEquals(5, out.level());
            assertEquals("world", out.world());
            assertEquals(600, out.x());
            assertEquals(100, out.y());
            assertEquals(2, out.z());
            assertEquals(72000L, out.fuelTicks());
            assertEquals(1.25, out.fuelBoost(), 1e-9, "fuelBoost 必须持久化（重启后加速不丢失）");
            assertEquals(2.0, out.multBoost(), 1e-9, "产量倍率必须持久化");
            assertEquals(18000L, out.multTicks());
            assertEquals("island-abc", out.islandId());
            assertEquals("auto_smelter", out.upgrade1());
            assertNull(out.upgrade2(), "未装备的模块槽应存 null");
            assertEquals("golden", out.skin());
            assertTrue(out.autoSell());
            assertEquals(12345L, out.totalProduced());
            assertEquals(1.30, out.permanentBoost(), 1e-9);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, out.inventory());

            // 二次 upsert（ON CONFLICT 更新路径）
            MinionData updated = new MinionData(
                    in.id(), in.owner(), in.type(), 6, in.world(), in.x(), in.y(), in.z(),
                    36000L, 1.10, 1.0, 0L, in.lastActiveEpochMs(), in.islandId(),
                    null, "compactor", null, null, in.skin(),
                    false, 99999L, 1.0, new byte[]{9});
            store.upsert(updated);
            MinionData reread = store.select(id).orElseThrow();
            assertEquals(6, reread.level());
            assertEquals(36000L, reread.fuelTicks());
            assertEquals(1.10, reread.fuelBoost(), 1e-9);
            assertEquals(1.0, reread.multBoost(), 1e-9);
            assertEquals(0L, reread.multTicks());
            assertNull(reread.upgrade1());
            assertEquals("compactor", reread.upgrade2());

            List<MinionData> all = store.selectAll();
            assertEquals(1, all.size());
        } finally {
            store.close();
        }
    }

    @Test
    void deleteRemovesRow(@TempDir Path dir) {
        SqliteMinionStore store = new SqliteMinionStore(dir.resolve("t2.db").toFile());
        store.init();
        try {
            UUID id = UUID.randomUUID();
            store.upsert(sample(id));
            store.delete(id);
            assertTrue(store.select(id).isEmpty(), "delete 后必须查不到");
            assertTrue(store.selectAll().isEmpty());
        } finally {
            store.close();
        }
    }

    /** 模拟线上旧库（缺 upgrade/fuel_boost 等列）：init() 必须自动补列且旧行可读。 */
    @Test
    void legacySchemaIsMigratedAndReadable(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("legacy.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            try (Statement st = c.createStatement()) {
                st.execute(LEGACY_DDL);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO minions (id, owner, type, level, xp, world, x, y, z, fuel_ticks, last_active, inventory) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int i = 1;
                ps.setString(i++, UUID.randomUUID().toString());
                ps.setString(i++, UUID.randomUUID().toString());
                ps.setString(i++, "miner");
                ps.setInt(i++, 3);
                ps.setLong(i++, 0L);
                ps.setString(i++, "world");
                ps.setInt(i++, 10);
                ps.setInt(i++, 64);
                ps.setInt(i++, -5);
                ps.setLong(i++, 72000L);
                ps.setLong(i++, 1700000000000L);
                ps.setBytes(i++, new byte[]{7, 7});
                ps.executeUpdate();
            }
        }

        SqliteMinionStore store = new SqliteMinionStore(db.toFile());
        store.init(); // 触发幂等迁移
        try {
            List<MinionData> all = store.selectAll();
            assertEquals(1, all.size(), "迁移后旧行必须仍可读");
            MinionData legacy = all.get(0);
            assertEquals("miner", legacy.type());
            assertEquals(3, legacy.level());
            // 新增列的默认值回填
            assertEquals(1.0, legacy.fuelBoost(), 1e-9, "迁移行 fuel_boost 默认 1.0");
            assertFalse(legacy.autoSell());
            assertEquals(0L, legacy.totalProduced());
            assertEquals(1.0, legacy.permanentBoost(), 1e-9);
            assertNull(legacy.upgrade1());
            assertNull(legacy.islandId());
        } finally {
            store.close();
        }
    }
}
