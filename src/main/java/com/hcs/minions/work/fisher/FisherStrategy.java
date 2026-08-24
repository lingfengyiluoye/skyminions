package com.hcs.minions.work.fisher;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 钓鱼策略：附近有水即工作，模拟钓鱼产出鱼类（不破坏方块）。
 */
public final class FisherStrategy implements MinionWorkStrategy {

    private static final List<Material> FISH = List.of(
            Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH
    );

    private final MinionTypeConfig cfg;

    public FisherStrategy(MinionTypeConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public MinionType type() {
        return MinionType.FISHER;
    }

    @Override
    public boolean canWork(WorkContext ctx) {
        // 轻量检查：anchor 周围 5x5x3 立体范围是否有水（不推进搜索游标）。
        // 高度含 -1..+1：仆从常被放在比水面高一格的岸边，只查同层会判为无水不工作
        Block anchor = ctx.anchor();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (anchor.getRelative(dx, dy, dz).getType() == Material.WATER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        // 单次 1 条，25% 概率多 1 条（配合较长冷却，避免产量爆炸）
        int n = 1 + (ctx.random().nextInt(4) == 0 ? 1 : 0);
        Material fish = FISH.get(ctx.random().nextInt(FISH.size()));
        return new WorkOutcome(true, n, List.of(new ItemStack(fish, n)));
    }

    @Override
    public int cooldownTicks() {
        return cfg.cooldownTicks();
    }
}
