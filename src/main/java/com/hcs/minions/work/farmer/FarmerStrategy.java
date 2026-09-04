package com.hcs.minions.work.farmer;

import com.hcs.minions.model.MinionBehavior;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.SimHarvest;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 农夫策略（模拟收割，不破坏作物）：统计工作范围内成熟作物的数量，
 * 产量与数量成正比：单次收获 = min(成熟数量, harvest-cap)，按作物占比分配产物。
 *
 * <p>作物保持成熟状态反复产出，无需收割/补种循环；只读方块类型与生长阶段，
 * 无破坏方块的开销。布局决定产量：种满 5x5 即最大产出。</p>
 */
public final class FarmerStrategy implements MinionWorkStrategy {

    /** 作物方块 -> 产物映射。 */
    private static final Map<Material, Material> PRODUCTS = Map.of(
            Material.WHEAT, Material.WHEAT,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT
    );

    @Override
    public MinionBehavior behavior() {
        return MinionBehavior.FARMING;
    }

    @Override
    public boolean canWork(WorkContext ctx) {
        return true;
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        // 统计范围内成熟作物（作物与仆从同层；纯内存读取，开销可忽略）
        Set<Material> allowed = ctx.cfg().targetsAt(ctx.minion().level()); // 分级解锁目标
        Map<Material, Integer> counts = new LinkedHashMap<>();
        Block anchor = ctx.anchor();
        int r = ctx.radius();
        int total = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = anchor.getRelative(dx, dy, dz);
                    if (isMatureCrop(b, allowed)) {
                        counts.merge(b.getType(), 1, Integer::sum);
                        total++;
                    }
                }
            }
        }
        if (total == 0) {
            return WorkOutcome.IDLE; // 范围内没有成熟作物 = 不工作（布局决定产量）
        }
        int harvest = Math.min(total, ctx.cfg().harvestCap());
        List<ItemStack> drops = SimHarvest.allocate(counts, harvest,
                m -> PRODUCTS.getOrDefault(m, m));
        return new WorkOutcome(true, harvest, drops);
    }

    private boolean isMatureCrop(Block block, Set<Material> allowed) {
        if (!allowed.contains(block.getType())) {
            return false;
        }
        if (block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() == ageable.getMaximumAge();
        }
        return true;
    }
}
