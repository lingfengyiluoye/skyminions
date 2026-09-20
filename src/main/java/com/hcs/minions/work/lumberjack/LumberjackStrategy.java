package com.hcs.minions.work.lumberjack;

import com.hcs.minions.model.MinionBehavior;
import com.hcs.minions.work.BlockOps;
import com.hcs.minions.work.ChunkGuard;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 伐木工策略：找到一根原木后，用广度优先搜索找出整棵树（相连的原木），一次性砍光，
 * 并在树根（最低处原木）位置自动补种对应树苗（循环再生长，无需玩家手动补种）。
 * 支持橡木/云杉/深色橡木/丛林等所有原木（含 2x2 树）。
 */
public final class LumberjackStrategy implements MinionWorkStrategy {

    private static final int MAX_TREE_LOGS = 128;
    /** 树的纵向扩散上限（最高树约 30 格）；水平方向仍受工作半径约束。 */
    private static final int MAX_TREE_HEIGHT = 32;

    /** 原木 -> 对应树苗（补种用；红树原木对应红树胎生苗）。映射来自 config.yml saplings 段。 */
    private static Map<Material, Material> saplings() {
        return com.hcs.minions.config.GameMaps.saplings();
    }

    @Override
    public MinionBehavior behavior() {
        return MinionBehavior.FORAGING;
    }

    @Override
    public boolean canWork(WorkContext ctx) {
        return true;
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        Set<Material> allowed = ctx.cfg().targetsAt(ctx.minion().level()); // 分级解锁目标
        Optional<Block> found = ctx.searcher().find(
                ctx.anchor(), ctx.radius(),
                b -> allowed.contains(b.getType()),
                ctx.minion()
        );
        if (found.isEmpty()) {
            return WorkOutcome.IDLE;
        }

        // 整树检测：BFS 收集相连原木，但限制在工作半径范围内（修复"虚空砍视野外木头"）
        List<Block> logs = collectTree(ctx.world(), found.get(), ctx.anchor(), ctx.radius(), allowed);
        Block root = lowest(logs);
        Material rootType = root.getType(); // 砍伐前记录树种，砍后原木会变空气
        List<ItemStack> allDrops = new ArrayList<>();
        for (Block log : logs) {
            allDrops.addAll(BlockOps.breakAndCollect(log));
        }
        replantSapling(root, rootType); // 砍完在树根补种对应树苗，循环再生长
        long xp = allDrops.stream().mapToLong(ItemStack::getAmount).sum();
        return new WorkOutcome(true, xp, allDrops);
    }

    /** 整棵树中最低的原木（树干基部；同高时取先遍历到的）。 */
    private static Block lowest(List<Block> logs) {
        Block root = logs.get(0);
        for (Block log : logs) {
            if (log.getY() < root.getY()) {
                root = log;
            }
        }
        return root;
    }

    /**
     * 在树根位置补种树苗：按树种放置对应树苗。每棵树只补一棵
     * （2x2 巨树也只补根部一棵，保持简单）。
     */
    private void replantSapling(Block root, Material rootType) {
        Material sapling = saplings().get(rootType);
        if (sapling != null && root.getType().isAir()) {
            root.setType(sapling, false); // 不触发物理更新，避免树苗被弹出检查干扰
        }
    }

    /**
     * 收集以 {@code start} 为起点的相连原木（BFS）。
     *
     * <p>关键约束：水平方向只在锚点（仆从）的切比雪夫距离 {@code radius} 内扩散，
     * 防止把视野外相连的树砍光；但纵向放宽为 {@link #MAX_TREE_HEIGHT}，
     * 因为树高（5~8 格，丛林巨树更高）远超工作半径，若纵向也限 radius
     * 就只能砍掉树根部几格，无法连锁整棵树。</p>
     *
     * <p>区块守卫：树冠很可能探出仆从所在区块（范围扩展模块下更常见）。
     * 未加载列的方块既不读取也不纳入结果——否则后续 breakAndCollect/setType
     * 就是对其他 region 方块的可变操作，Folia 线程校验会抛异常。</p>
     */
    private List<Block> collectTree(org.bukkit.World world, Block start, Block anchor, int radius, Set<Material> allowed) {
        int ax = anchor.getX();
        int ay = anchor.getY();
        int az = anchor.getZ();
        boolean[][] loaded = ChunkGuard.loaded(world, anchor, radius);
        List<Block> out = new ArrayList<>();
        Deque<Block> queue = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        int[][] dirs = {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
        };
        while (!queue.isEmpty() && out.size() < MAX_TREE_LOGS) {
            Block current = queue.poll();
            out.add(current);
            for (int[] d : dirs) {
                Block next = current.getRelative(d[0], d[1], d[2]);
                if (visited.contains(next) || !allowed.contains(next.getType())) {
                    continue;
                }
                // 水平范围约束：超出工作半径的原木不扩散（防虚空砍树）
                int dx = next.getX() - ax;
                int dz = next.getZ() - az;
                if (Math.abs(dx) > radius || Math.abs(dz) > radius) {
                    continue;
                }
                // 区块加载守卫：未加载列的原木不纳入（跨 region 方块不可在本 region 线程破坏）
                if (!ChunkGuard.isLoaded(loaded, dx, dz, radius)) {
                    continue;
                }
                // 纵向约束：只限树高，不受工作半径影响
                if (Math.abs(next.getY() - ay) > MAX_TREE_HEIGHT) {
                    continue;
                }
                visited.add(next);
                queue.add(next);
            }
        }
        return out;
    }
}
