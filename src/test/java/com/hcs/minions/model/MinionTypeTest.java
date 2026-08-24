package com.hcs.minions.model;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** {@link MinionType} 注册表（配置驱动身份层）测试。 */
class MinionTypeTest {

    private static MinionType kind(String key, String name, MinionBehavior behavior, MinionCategory category) {
        return MinionType.of(key, name, behavior, category, Material.STONE);
    }

    @Test
    void loadAllRegistersCaseInsensitiveLookup() {
        MinionType.loadAll(List.of(
                kind("煤矿仆从", "煤矿仆从", MinionBehavior.MINING, MinionCategory.MINING),
                kind("铁矿仆从", "铁矿仆从", MinionBehavior.MINING, MinionCategory.MINING),
                kind("小麦仆从", "小麦仆从", MinionBehavior.FARMING, MinionCategory.FARMING)));
        assertEquals(Optional.of("煤矿仆从"), MinionType.fromKey("煤矿仆从").map(MinionType::key));
        assertTrue(MinionType.fromKey("unknown").isEmpty());
        assertTrue(MinionType.fromKey(null).isEmpty());
    }

    @Test
    void legacyAliasesResolveToFinalChineseKinds() {
        // 存量兼容：英文旧 key 与过渡中文 key 都必须解析到最终「××仆从」
        MinionType.loadAll(List.of(
                kind("煤矿仆从", "煤矿仆从", MinionBehavior.MINING, MinionCategory.MINING),
                kind("牛仆从", "牛仆从", MinionBehavior.RANCHING, MinionCategory.SPECIAL),
                kind("僵尸仆从", "僵尸仆从", MinionBehavior.COMBAT, MinionCategory.COMBAT)));
        assertEquals(Optional.of("煤矿仆从"), MinionType.fromKey("miner").map(MinionType::key));
        assertEquals(Optional.of("煤矿仆从"), MinionType.fromKey("MINER").map(MinionType::key));
        assertEquals(Optional.of("煤矿仆从"), MinionType.fromKey("煤矿").map(MinionType::key));
        assertEquals(Optional.of("牛仆从"), MinionType.fromKey("rancher").map(MinionType::key));
        assertEquals(Optional.of("牛仆从"), MinionType.fromKey("养牛").map(MinionType::key));
        assertEquals(Optional.of("僵尸仆从"), MinionType.fromKey("slayer").map(MinionType::key));
        // 未登记别名的 key 仍为空
        assertTrue(MinionType.fromKey("blaze_minion").isEmpty());
    }

    @Test
    void registryPreservesDeclarationOrder() {
        List<String> keys = List.of("a_mining", "b_farming", "c_foraging");
        MinionType.loadAll(List.of(
                kind(keys.get(0), "A", MinionBehavior.MINING, MinionCategory.MINING),
                kind(keys.get(1), "B", MinionBehavior.FARMING, MinionCategory.FARMING),
                kind(keys.get(2), "C", MinionBehavior.FORAGING, MinionCategory.FORAGING)));
        assertEquals(keys, MinionType.all().stream().map(MinionType::key).toList());
    }

    @Test
    void emptyRegistryFallsBackToUnknown() {
        MinionType.loadAll(List.of());
        assertEquals(1, MinionType.all().size());
        assertEquals(MinionType.fallback(), MinionType.all().iterator().next());
        assertEquals(Optional.of(MinionType.fallback()), MinionType.fromKey("unknown"));
        assertEquals(MinionBehavior.MINING, MinionType.fallback().behavior());
    }

    @Test
    void canonicalInstancesSupportIdentityComparison() {
        MinionType.loadAll(List.of(kind("k", "K", MinionBehavior.COMBAT, MinionCategory.COMBAT)));
        assertSame(MinionType.fromKey("k").orElseThrow(), MinionType.fromKey("K").orElseThrow());
    }

    @Test
    void blankKeysAreSkippedDuringLoad() {
        MinionType.loadAll(List.of(
                kind("", "blank", MinionBehavior.MINING, MinionCategory.MINING),
                kind("ok", "ok", MinionBehavior.MINING, MinionCategory.MINING)));
        assertEquals(1, MinionType.all().size());
        assertEquals("ok", MinionType.all().iterator().next().key());
    }
}
