package com.hcs.minions.work.farmer;

import com.hcs.minions.model.BlockLocation;
import com.hcs.minions.model.MinionBehavior;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.SimHarvest;
import com.hcs.minions.work.WorkContext;
import com.hcs.minions.work.WorkOutcome;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
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
 *
 * <p>理想布局（GUI 开关）：自动把范围内「空气位 + 下方泥土系方块」开垦为耕地并播种
 * （每周期最多 {@link #PLANTS_PER_CYCLE} 格，只填空地不破坏玩家建筑）；
 * 播种后由原版随机 tick 自然生长，成熟后进入正常模拟收割。关闭/拾取时还原耕地与作物。</p>
 */
public final class FarmerStrategy implements MinionWorkStrategy {

    /** 每个工作周期最多开垦播种格数（摊薄单次开销）。 */
    private static final int PLANTS_PER_CYCLE = 3;

    /** 可开垦为耕地的地面方块。 */
    private static final Set<Material> TILLABLE =
            EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL);

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
        if (ctx.minion().idealLayout()) {
            ensureFarm(ctx); // 自动布局：补种缺口的耕地（不阻塞本次收割）
        }
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

    /**
     * 自动开垦播种：扫描与仆从同层的空气位，下方为可耕地系方块时开垦并播种。
     * 每周期最多 {@link #PLANTS_PER_CYCLE} 格；只填空地，绝不覆盖玩家建筑；
     * 开垦的耕地与播下的种子都登记到布局块（关闭/拾取时还原）。
     */
    private void ensureFarm(WorkContext ctx) {
        List<Material> crops = List.copyOf(ctx.cfg().targets());
        if (crops.isEmpty()) {
            return;
        }
        Block anchor = ctx.anchor();
        int r = ctx.radius();
        int planted = 0;
        for (int dx = -r; dx <= r && planted < PLANTS_PER_CYCLE; dx++) {
            for (int dz = -r; dz <= r && planted < PLANTS_PER_CYCLE; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 中心留给仆从
                }
                Block spot = anchor.getRelative(dx, 0, dz);
                if (!spot.getType().isAir()) {
                    continue; // 只填空气位
                }
                Block ground = spot.getRelative(BlockFace.DOWN);
                Material groundType = ground.getType();
                if (groundType != Material.FARMLAND && !TILLABLE.contains(groundType)) {
                    continue;
                }
                if (groundType != Material.FARMLAND) { // 泥土/草方块 → 耕地（登记原方块）
                    ground.setType(Material.FARMLAND, false);
                    ctx.minion().addLayoutBlock(BlockLocation.of(ground), groundType);
                }
                // 按位置轮换作物种类，形成混合农田（对齐 Hypixel 农场观感）
                Material crop = crops.get(Math.floorMod(dx + 2 * dz, crops.size()));
                spot.setType(crop, false);
                ctx.minion().addLayoutBlock(BlockLocation.of(spot), Material.AIR);
                planted++;
            }
        }
    }
}
