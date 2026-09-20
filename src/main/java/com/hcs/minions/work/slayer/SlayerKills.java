package com.hcs.minions.work.slayer;

import org.bukkit.entity.Entity;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 仆从击杀标记：猎魔仆从用 {@code damage()} 击杀怪物以触发 EntityDeathEvent（兼容任务/统计类插件），
 * 但掉落由仆从模拟 loot 表给出，不能叠加原版自然掉落 —— 被标记的实体死亡时清空自然掉落与经验。
 *
 * <p>标记带时间戳：正常路径由 {@code SlayerStrategy} 的实体 region 任务内 mark/unmark 配对消费；
 * 若实体在任务执行前被区块卸载/插件移除（实体调度任务被取消、标记无人撤销），
 * 旧标记会在 {@link #MARK_TTL_MS} 后过期作废，避免重载区块后同一 UUID 的怪物被误清掉落。
 */
public final class SlayerKills {

    /** 标记有效期：实体 region 任务至迟数 tick 内执行，5 分钟为极端延迟留足余量。 */
    private static final long MARK_TTL_MS = 300_000L;

    /** 实体 UUID -> 标记时间（epoch ms）。 */
    private static final Map<UUID, Long> MARKED = new ConcurrentHashMap<>();

    private SlayerKills() {
    }

    public static void mark(Entity entity) {
        long now = System.currentTimeMillis();
        MARKED.put(entity.getUniqueId(), now);
        prune(now);
    }

    /** 消费标记：实体确为仆从击杀返回 true（标记随即移除，不残留）。 */
    public static boolean consume(Entity entity) {
        UUID id = entity.getUniqueId();
        Long at = MARKED.get(id);
        if (at == null) {
            return false;
        }
        MARKED.remove(id);
        return System.currentTimeMillis() - at <= MARK_TTL_MS;
    }

    /** 伤害未致死/实体已消失时撤销标记，避免后续他人击杀被误吞掉落。 */
    public static void unmark(Entity entity) {
        MARKED.remove(entity.getUniqueId());
    }

    /** 清理过期标记（mark 时顺带执行，成本 O(剩余标记数) 且仅在标记非空时发生）。 */
    private static void prune(long now) {
        for (Iterator<Map.Entry<UUID, Long>> it = MARKED.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > MARK_TTL_MS) {
                it.remove();
            }
        }
    }
}
