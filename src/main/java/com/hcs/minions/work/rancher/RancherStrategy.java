package com.hcs.minions.work.rancher;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 牧民策略（对齐 Hypixel Cow/Chicken/Sheep 畜牧仆从，纯模拟版）：
 * 每次工作模拟一次"饲养→宰杀"，从畜牧池随机一种动物按战利品表 roll 掉落入仓。
 *
 * <p>不再 spawn 真实动物实体：真实实体方案下玩家可多仆从大规模养殖刷实体，
 * 动物堆积会拖垮服务器（实体 tick 压力），且存在引种/繁殖状态被玩家
 * 手动干预（杀光/圈走）导致仆从空转的可卡点。纯模拟与矿工/农夫等
 * "模拟掉落入仓"口径一致，产出稳定且零实体负担。</p>
 */
public final class RancherStrategy implements MinionWorkStrategy {

    /** 畜牧池：每次模拟宰杀随机选取一种动物。 */
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
        return true; // 纯模拟产出，无前置条件
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        Random random = ctx.random();
        EntityType kind = POOL.get(random.nextInt(POOL.size()));
        List<ItemStack> drops = lootOf(kind, random);
        long xp = drops.stream().mapToLong(ItemStack::getAmount).sum();
        return new WorkOutcome(true, xp, drops);
    }

    @Override
    public int cooldownTicks() {
        return cfg.cooldownTicks();
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
