package com.hcs.minions.work.miner;

import com.hcs.minions.model.MinionBehavior;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.SimHarvest;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 矿工策略（模拟采集，不破坏方块）：统计工作范围内（5x5x3 立体）可采矿物的数量，
 * 产量与数量成正比：单次收获 = min(可采数量, harvest-cap)，按矿石占比分配产物。
 *
 * <p>只读 {@code Block.getType()}（纯内存读取），不产生光照重算/方块更新包/
 * 物理更新等破坏方块的开销，玩家摆满矿石即可持续产出，无需反复补矿。</p>
 */
public final class MinerStrategy implements MinionWorkStrategy {

    /** 矿石方块 -> 产物映射（模拟采集不出原石形态的矿物产物）。 */
    private static final Map<Material, Material> PRODUCTS = buildProducts();

    private static Map<Material, Material> buildProducts() {
        Map<Material, Material> m = new LinkedHashMap<>();
        m.put(Material.COAL_ORE, Material.COAL);
        m.put(Material.DEEPSLATE_COAL_ORE, Material.COAL);
        m.put(Material.IRON_ORE, Material.RAW_IRON);
        m.put(Material.DEEPSLATE_IRON_ORE, Material.RAW_IRON);
        m.put(Material.COPPER_ORE, Material.RAW_COPPER);
        m.put(Material.DEEPSLATE_COPPER_ORE, Material.RAW_COPPER);
        m.put(Material.GOLD_ORE, Material.RAW_GOLD);
        m.put(Material.DEEPSLATE_GOLD_ORE, Material.RAW_GOLD);
        m.put(Material.REDSTONE_ORE, Material.REDSTONE);
        m.put(Material.DEEPSLATE_REDSTONE_ORE, Material.REDSTONE);
        m.put(Material.LAPIS_ORE, Material.LAPIS_LAZULI);
        m.put(Material.DEEPSLATE_LAPIS_ORE, Material.LAPIS_LAZULI);
        m.put(Material.EMERALD_ORE, Material.EMERALD);
        m.put(Material.DEEPSLATE_EMERALD_ORE, Material.EMERALD);
        m.put(Material.NETHER_QUARTZ_ORE, Material.QUARTZ);
        m.put(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP);
        m.put(Material.DIAMOND_ORE, Material.DIAMOND);
        m.put(Material.DEEPSLATE_DIAMOND_ORE, Material.DIAMOND);
        m.put(Material.STONE, Material.COBBLESTONE);
        m.put(Material.COBBLESTONE, Material.COBBLESTONE);
        return m;
    }

    @Override
    public MinionBehavior behavior() {
        return MinionBehavior.MINING;
    }

    @Override
    public boolean canWork(WorkContext ctx) {
        return true;
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        // 统计范围内可采矿物（水平 5x5，纵向 ±1；纯内存读取，开销可忽略）
        Set<Material> allowed = ctx.cfg().targetsAt(ctx.minion().level()); // 分级解锁：低 Tier 采不了高价值矿
        Map<Material, Integer> counts = new LinkedHashMap<>();
        Block anchor = ctx.anchor();
        int r = ctx.radius();
        int total = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    Material m = anchor.getRelative(dx, dy, dz).getType();
                    if (allowed.contains(m)) {
                        counts.merge(m, 1, Integer::sum);
                        total++;
                    }
                }
            }
        }
        if (total == 0) {
            return WorkOutcome.IDLE; // 范围内没有可采矿物 = 不工作（布局决定产量）
        }
        int harvest = Math.min(total, ctx.cfg().harvestCap());
        List<ItemStack> drops = SimHarvest.allocate(counts, harvest,
                m -> PRODUCTS.getOrDefault(m, m));
        return new WorkOutcome(true, harvest, drops);
    }
}
