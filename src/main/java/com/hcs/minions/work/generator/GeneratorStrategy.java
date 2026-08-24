package com.hcs.minions.work.generator;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.BlockLocation;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionType;
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
 *
 * <p>理想布局（GUI 开关）：自动在仆从上方一层摆水 + 岩浆搭建经典刷石机：
 * 水在仆从朝向的左侧 1 格、岩浆源在右侧 2 格（随放置朝向旋转），两股流体在中间生成点相遇产出圆石；
 * 只在空气位摆放，不破坏玩家建筑，关闭/拾取时还原流体块。</p>
 */
public final class GeneratorStrategy implements MinionWorkStrategy {

    private final MinionTypeConfig cfg;

    public GeneratorStrategy(MinionTypeConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public MinionType type() {
        return MinionType.COBBLE;
    }

    @Override
    public boolean canWork(WorkContext ctx) {
        return true;
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        if (ctx.minion().idealLayout()) {
            return idealLayoutWork(ctx);
        }
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

    /**
     * 理想布局工作循环：
     * <ul>
     *   <li>未搭建：生成点/水源位/岩浆位均为空气时才摆放（防破坏玩家建筑），
     *       水在仆从朝向左侧 1 格、岩浆源在右侧 2 格（岩浆流 1 格后与水相遇，避免岩浆源触水变黑曜石）；</li>
     *   <li>已搭建：只采生成点的圆石，采后流体自动重新生成。</li>
     * </ul>
     */
    private WorkOutcome idealLayoutWork(WorkContext ctx) {
        Minion minion = ctx.minion();
        Block gen = ctx.anchor().getRelative(BlockFace.UP);
        if (minion.hasLayoutBlocks()) {
            if (gen.getType() == Material.COBBLESTONE) {
                List<ItemStack> drops = BlockOps.breakAndCollect(gen);
                long xp = drops.stream().mapToLong(ItemStack::getAmount).sum();
                return new WorkOutcome(true, xp, drops);
            }
            return WorkOutcome.IDLE; // 等待流体物理生成下一块圆石
        }
        // 流体方向随仆从放置朝向旋转（不再硬编码东西向）
        BlockFace left = rotateLeft(facingOf(minion.facing()));
        Block water = gen.getRelative(left);
        Block lava = gen.getRelative(left.getOppositeFace(), 2);
        if (!gen.getType().isAir() || !water.getType().isAir() || !lava.getType().isAir()) {
            return WorkOutcome.IDLE; // 空间被占用：不破坏玩家方块，布局无法搭建
        }
        water.setType(Material.WATER, false);
        lava.setType(Material.LAVA, false);
        minion.addLayoutBlock(BlockLocation.of(water));
        minion.addLayoutBlock(BlockLocation.of(lava));
        return new WorkOutcome(true, 0, List.of());
    }

    /** 将放置 yaw 量化为四方向（yaw 0=+Z 南，90=-X 西，180=-Z 北，270=+X 东）。 */
    private static BlockFace facingOf(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (q) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    /** 逆时针旋转 90°（面朝方向的左侧）。 */
    private static BlockFace rotateLeft(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    @Override
    public int cooldownTicks() {
        return cfg.cooldownTicks();
    }
}
