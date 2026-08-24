package com.hcs.minions.work.rancher;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 牧民策略（对齐 Hypixel Cow/Chicken/Sheep 畜牧仆从）：
 * 照看范围内牧场动物 —— 空场引种、成年不足繁殖、达到上限宰杀获取战利品。
 *
 * <p>宰杀直接 {@code remove()} 实体并按近似原版的区间 roll 掉落，
 * 不生成实体战利品（与其它策略的"模拟掉落入仓"口径一致）。
 * 生成的动物 {@code setRemoveWhenFarAway(false)}，区块卸载不消失。</p>
 */
public final class RancherStrategy implements MinionWorkStrategy {

    /** 牧场成年动物上限（对齐 Hypixel 畜牧仆从约 4 只的圈养规模）。 */
    static final int MAX_ADULTS = 4;

    /** 支持的牧场动物池。 */
    private static final List<EntityType> POOL = List.of(
            EntityType.COW, EntityType.SHEEP, EntityType.CHICKEN, EntityType.PIG);

    /** 宰杀战利品表：[0]=主产物，[1]=副产物（无副产物则只有主产物），近似原版掉落区间。 */
    private static final Map<EntityType, Material[]> LOOT = Map.of(
            EntityType.COW, new Material[]{Material.BEEF, Material.LEATHER},
            EntityType.SHEEP, new Material[]{Material.MUTTON, Material.WHITE_WOOL},
            EntityType.CHICKEN, new Material[]{Material.CHICKEN, Material.FEATHER},
            EntityType.PIG, new Material[]{Material.PORKCHOP}
    );

    private final MinionTypeConfig cfg;

    public RancherStrategy(MinionTypeConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public MinionType type() {
        return MinionType.RANCHER;
    }

    @Override
    public boolean canWork(WorkContext ctx) {
        return true; // 引种/繁殖/宰杀总有一个可做，不依赖特定目标方块
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        Location center = ctx.anchor().getLocation().add(0.5, 1.0, 0.5);
        double r = ctx.radius() + 0.5;
        List<Animals> adults = new ArrayList<>(4);
        List<Animals> babies = new ArrayList<>(4);
        for (Entity e : ctx.world().getNearbyEntities(center, r, r, r)) {
            if (e instanceof Animals a && LOOT.containsKey(e.getType())) {
                (a.isAdult() ? adults : babies).add(a);
            }
        }

        Random random = ctx.random();
        if (adults.isEmpty() && babies.isEmpty()) {
            // 空场引种：生成两只成年（随机种类），本次无产出
            spawn(ctx, POOL.get(random.nextInt(POOL.size())), true);
            spawn(ctx, POOL.get(random.nextInt(POOL.size())), true);
            return new WorkOutcome(true, 0, List.of());
        }
        if (adults.size() < MAX_ADULTS) {
            // 繁殖：沿用现有种群随机种类的血脉，生成幼崽
            EntityType kind = adults.isEmpty()
                    ? POOL.get(random.nextInt(POOL.size()))
                    : adults.get(random.nextInt(adults.size())).getType();
            spawn(ctx, kind, false);
            return new WorkOutcome(true, 0, List.of());
        }
        // 成年达到上限：宰杀随机一只成年，模拟原版掉落
        Animals victim = adults.get(random.nextInt(adults.size()));
        List<ItemStack> drops = lootOf(victim.getType(), random);
        victim.remove();
        long xp = drops.stream().mapToLong(ItemStack::getAmount).sum();
        return new WorkOutcome(true, xp, drops);
    }

    @Override
    public int cooldownTicks() {
        return cfg.cooldownTicks();
    }

    /** 在锚点周围随机"固体上方空气位"生成动物（重试若干次找不到则放弃）。 */
    private void spawn(WorkContext ctx, EntityType kind, boolean adult) {
        Random random = ctx.random();
        for (int i = 0; i < 6; i++) {
            int dx = random.nextInt(-ctx.radius(), ctx.radius() + 1);
            int dz = random.nextInt(-ctx.radius(), ctx.radius() + 1);
            Block base = ctx.anchor().getRelative(dx, 1, dz);
            if (!base.getType().isAir() || !base.getRelative(BlockFace.DOWN).getType().isSolid()) {
                continue;
            }
            Location loc = base.getLocation().add(0.5, 0, 0.5);
            Animals animal = (Animals) ctx.world().spawn(loc, kind.getEntityClass());
            animal.setRemoveWhenFarAway(false);
            if (adult) {
                animal.setAdult();
            } else {
                animal.setBaby();
            }
            return;
        }
    }

    /** 纯函数：按战利品表 roll 一次宰杀掉落（主产物 1-2 件，副产物约 2/3 概率 1-2 件）。 */
    static List<ItemStack> lootOf(EntityType type, Random random) {
        Material[] loot = LOOT.get(type);
        List<ItemStack> drops = new ArrayList<>(2);
        drops.add(new ItemStack(loot[0], 1 + random.nextInt(2)));
        if (loot.length > 1 && random.nextInt(3) > 0) {
            drops.add(new ItemStack(loot[1], 1 + random.nextInt(2)));
        }
        return drops;
    }
}
