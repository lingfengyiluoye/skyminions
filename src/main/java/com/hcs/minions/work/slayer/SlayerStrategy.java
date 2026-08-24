package com.hcs.minions.work.slayer;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.MinionBehavior;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Slime;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static org.bukkit.Material.BLAZE_POWDER;
import static org.bukkit.Material.GLOWSTONE_DUST;
import static org.bukkit.Material.GUNPOWDER;
import static org.bukkit.Material.REDSTONE;
import static org.bukkit.Material.SPIDER_EYE;
import static org.bukkit.Material.STICK;
import static org.bukkit.Material.SUGAR;

/**
 * 猎魔策略（范围杀敌，对齐矿工等方块检测型仆从的"检测周围"模式）：
 *
 * <p>每次工作先扫描工作范围内的真实敌对生物（僵尸/骷髅/苦力怕/蜘蛛等），
 * 有则击杀最近的一只并按其种类给出对应掉落（真实 loot 表，受抢夺等影响）；
 * 范围内无实体怪物时回退为"模拟击杀"随机产出怪物掉落 —— 空岛无刷怪塔也仍有基础产出。
 * 不再出现"面前有僵尸不杀、却虚空杀敌"的问题。</p>
 */
public final class SlayerStrategy implements MinionWorkStrategy {

    /** 模拟击杀（范围内无真实怪物时）的随机掉落池。 */
    private static final List<Material> GENERIC_DROPS = List.of(
            Material.ROTTEN_FLESH, Material.BONE, Material.SPIDER_EYE,
            Material.GUNPOWDER, Material.STRING, Material.ENDER_PEARL
    );

    /** 女巫掉落池（对齐原版女巫的多种药剂材料掉落）。 */
    private static final Material[] WITCH_DROPS = {
            SUGAR, REDSTONE, GLOWSTONE_DUST, GUNPOWDER, SPIDER_EYE, STICK, BLAZE_POWDER
    };

    /** Boss 级生物：仆从不攻击（防 damage(9999) 白嫖 Boss 击杀/成就联动）。 */
    private static boolean isBoss(org.bukkit.entity.Entity e) {
        return e instanceof org.bukkit.entity.Wither
                || e instanceof org.bukkit.entity.Warden
                || e instanceof org.bukkit.entity.ElderGuardian;
    }

    @Override
    public MinionBehavior behavior() {
        return MinionBehavior.COMBAT;
    }

    @Override
    public boolean canWork(WorkContext ctx) {
        return true; // 无怪时也模拟产出，因此不做前置怪物检查
    }

    @Override
    public WorkOutcome performWork(WorkContext ctx) {
        Entity victim = findHostile(ctx);
        if (victim instanceof LivingEntity living) {
            // 真实击杀：用 damage() 触发 EntityDeathEvent（任务/统计类插件可联动）；
            // 掉落由下方模拟 loot 表给出，标记后由监听器清掉自然掉落避免双份
            SlayerKills.mark(living);
            living.damage(9999.0);
            if (!living.isDead() && living.isValid()) {
                SlayerKills.unmark(living); // 伤害未致死（如抗性/无敌），撤销标记
            }
            List<ItemStack> drops = lootOf(victim, ctx.random());
            long xp = Math.max(1, drops.stream().mapToLong(ItemStack::getAmount).sum());
            return new WorkOutcome(true, xp, drops);
        }
        // 范围内无怪物：模拟击杀 1~2 个掉落
        int n = 1 + ctx.random().nextInt(2);
        Material drop = GENERIC_DROPS.get(ctx.random().nextInt(GENERIC_DROPS.size()));
        return new WorkOutcome(true, n, List.of(new ItemStack(drop, n)));
    }

    /** 按怪物种类映射原版掉落（数量随机，对齐自然击杀的产出形态）。 */
    private static List<ItemStack> lootOf(Entity victim, java.util.Random rnd) {
        List<ItemStack> drops = new ArrayList<>();
        switch (victim.getType()) {
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED ->
                    add(drops, Material.ROTTEN_FLESH, 1 + rnd.nextInt(2));
            case SKELETON, STRAY -> {
                add(drops, Material.BONE, 1 + rnd.nextInt(2));
                add(drops, Material.ARROW, rnd.nextInt(3));
            }
            case CREEPER -> add(drops, Material.GUNPOWDER, 1 + rnd.nextInt(2));
            case SPIDER, CAVE_SPIDER -> {
                add(drops, Material.STRING, 1 + rnd.nextInt(2));
                add(drops, Material.SPIDER_EYE, rnd.nextInt(2));
            }
            case ENDERMAN -> add(drops, Material.ENDER_PEARL, rnd.nextInt(2));
            case BLAZE -> add(drops, Material.BLAZE_ROD, rnd.nextInt(2));
            case SLIME -> add(drops, Material.SLIME_BALL, rnd.nextInt(3));
            case MAGMA_CUBE -> add(drops, Material.MAGMA_CREAM, rnd.nextInt(3));
            case PHANTOM -> add(drops, Material.PHANTOM_MEMBRANE, rnd.nextInt(2));
            case WITHER_SKELETON -> {
                add(drops, Material.BONE, 1 + rnd.nextInt(2));
                add(drops, Material.COAL, rnd.nextInt(2));
            }
            case GHAST -> {
                add(drops, Material.GHAST_TEAR, rnd.nextInt(2));
                add(drops, Material.GUNPOWDER, rnd.nextInt(3));
            }
            case ZOMBIFIED_PIGLIN, PIGLIN, PIGLIN_BRUTE ->
                    add(drops, Material.GOLD_NUGGET, rnd.nextInt(3));
            case WITCH -> add(drops, WITCH_DROPS[rnd.nextInt(WITCH_DROPS.length)], 1);
            default -> add(drops, GENERIC_DROPS.get(rnd.nextInt(GENERIC_DROPS.size())), 1);
        }
        return drops;
    }

    private static void add(List<ItemStack> drops, Material m, int n) {
        if (n > 0) {
            drops.add(new ItemStack(m, n));
        }
    }

    /**
     * 在工作范围内寻找最近的敌对生物（与方块搜索同样的半径语义）。
     * 类型配置了 preferred-targets 时优先在其中锁定（两轮扫描：先定向后通用）。
     * Monster 覆盖全部原版敌对怪；Slime/Phantom 不是 Monster 子类，单独补上。
     */
    private Entity findHostile(WorkContext ctx) {
        Location center = ctx.anchor().getLocation().add(0.5, 0.5, 0.5);
        double r = ctx.radius() + 0.5;
        Entity nearest = null;
        Entity preferred = null;
        double best = Double.MAX_VALUE;
        for (Entity e : ctx.world().getNearbyEntities(center, r, r + 1, r)) {
            if (!(e instanceof Monster) && !(e instanceof Slime) && !(e instanceof Phantom)) {
                continue;
            }
            if (isBoss(e) || e.isDead() || !e.isValid()) {
                continue;
            }
            double d = e.getLocation().distanceSquared(center);
            if (d < best) {
                best = d;
                nearest = e;
            }
            if (preferred == null && ctx.cfg().isPreferred(e.getType())) {
                preferred = e; // 定向目标取第一只命中即可（范围小，距离差异可忽略）
            }
        }
        return preferred != null ? preferred : nearest;
    }

    /** 离线结算：范围内无实体怪，走通用模拟掉落池聚合（与在线回退口径一致）。 */
    @Override
    public List<ItemStack> offlineYield(MinionTypeConfig cfg, int actions, java.util.Random rnd, long maxUnits) {
        java.util.Map<Material, Integer> agg = new java.util.EnumMap<>(Material.class);
        long units = 0;
        for (int i = 0; i < actions && units < maxUnits; i++) {
            int n = 1 + rnd.nextInt(2);
            Material drop = GENERIC_DROPS.get(rnd.nextInt(GENERIC_DROPS.size()));
            for (int k = 0; k < n && units < maxUnits; k++) {
                agg.merge(drop, 1, Integer::sum);
                units++;
            }
        }
        return MinionWorkStrategy.mergeToStacks(agg);
    }
}
