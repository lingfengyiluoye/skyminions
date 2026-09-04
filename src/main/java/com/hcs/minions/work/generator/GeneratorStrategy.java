package com.hcs.minions.work.generator;

import com.hcs.minions.model.MinionBehavior;
import com.hcs.minions.work.BlockOps;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * 生成器策略（对齐 Hypixel Cobblestone Minion 的圆石生成器玩法）：
 * 范围内已有圆石则采集掉落；没有则在「固体方块上方的空气位」生成一块圆石，
 * 呈现"生成 → 采集"交替节奏 —— 无需玩家自搭刷石机。
 */
public final class GeneratorStrategy implements MinionWorkStrategy {

    @Override
    public MinionBehavior behavior() {
        return MinionBehavior.GENERATOR;
    }

    @Override
    public boolean canWork(WorkContext ctx) {
        return true;
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        // 优先采集已生成的圆石
        Optional<Block> cobble = ctx.searcher().find(ctx.world(), ctx.anchor(), ctx.radius(),
                b -> b.getType() == Material.COBBLESTONE, ctx.minion());
        if (cobble.isPresent()) {
            List<ItemStack> drops = BlockOps.breakAndCollect(cobble.get());
            long xp = drops.stream().mapToLong(ItemStack::getAmount).sum();
            return new WorkOutcome(true, xp, drops);
        }
        // 无圆石可采：在固体上方空气位生成一块（本次无产出，下次工作采集）
        Optional<Block> slot = ctx.searcher().find(ctx.world(), ctx.anchor(), ctx.radius(),
                b -> b.getType().isAir() && b.getRelative(BlockFace.DOWN).getType().isSolid(),
                ctx.minion());
        if (slot.isPresent()) {
            slot.get().setType(Material.COBBLESTONE);
            return new WorkOutcome(true, 0, List.of());
        }
        return WorkOutcome.IDLE;
    }
}
