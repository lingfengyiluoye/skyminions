package com.hcs.minions.work;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MinionWorkStrategy#mergeToStacks} 防御性测试。
 *
 * <p>注意：构造 {@code new ItemStack} 需要 Bukkit Registry（服务端运行时才就绪），
 * 单测环境只能验证「不产生任何物品堆」的防御路径——恰好覆盖本次修复的
 * 负数截断问题。堆叠展开路径由服务端实际运行覆盖。</p>
 */
class MergeToStacksTest {

    @Test
    void negativeCountIsIgnoredNotCrashed() {
        // 离线产出 (int) total 在极大 maxUnits 下可能截断为负：必须安全忽略而非抛错
        Map<Material, Integer> counts = new LinkedHashMap<>();
        counts.put(Material.STONE, -5);
        assertTrue(MinionWorkStrategy.mergeToStacks(counts).isEmpty());
    }

    @Test
    void zeroCountProducesNothing() {
        Map<Material, Integer> counts = new LinkedHashMap<>();
        counts.put(Material.STONE, 0);
        assertTrue(MinionWorkStrategy.mergeToStacks(counts).isEmpty());
    }

    @Test
    void emptyMapProducesNothing() {
        assertTrue(MinionWorkStrategy.mergeToStacks(new LinkedHashMap<>()).isEmpty());
    }
}
