package com.hcs.minions.work;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.MinionBehavior;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 仆从工作策略接口（策略模式）。
 * 调度器只面向本接口编程（canWork / performWork），
 * 严禁 if-else 堆砌类型判断 —— 类型到策略的映射由 WorkStrategyRegistry 统一查表。
 *
 * <p>冷却与产量等配置一律经 {@link WorkContext#cfg()} 实时读取（热重载即时生效），
 * 策略实现不得持有配置引用。</p>
 */
public interface MinionWorkStrategy {

    /** 本策略服务的行为原型。 */
    MinionBehavior behavior();

    /** 当前周期是否允许工作（燃料、环境等由 Manager 统一判断，此处只判断类型语义）。 */
    boolean canWork(WorkContext ctx);

    /**
     * 执行一次限流搜索 + 破坏，返回结果。
     * 必须在主线程/区域线程调用（内部含 setType 等方块操作）。
     */
    WorkOutcome performWork(WorkContext ctx);

    /**
     * 离线结算：按基础速度估算 {@code actions} 次动作的聚合产出。
     * 不触碰方块/世界，纯内存 roll；总件数不得超过 {@code maxUnits}（仓储空位天花板）。
     * 默认实现 = product × harvestCap × actions（统计型策略语义）；
     * 需要随机掉落形态的类型（钓鱼/牧民/猎魔）自行覆写。
     */
    default List<ItemStack> offlineYield(MinionTypeConfig cfg, int actions, Random rnd, long maxUnits) {
        long total = Math.min((long) cfg.harvestCap() * Math.max(0, actions), maxUnits);
        if (total <= 0) {
            return List.of();
        }
        return mergeToStacks(Map.of(cfg.product(), (int) total));
    }

    /** 把 物料->数量 聚合表展开为最大堆叠 64 的物品堆列表。 */
    static List<ItemStack> mergeToStacks(Map<Material, Integer> counts) {
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            int remaining = e.getValue();
            int maxSize = Math.max(1, e.getKey().getMaxStackSize());
            while (remaining > 0) {
                int take = Math.min(maxSize, remaining);
                out.add(new ItemStack(e.getKey(), take));
                remaining -= take;
            }
        }
        return out;
    }
}
