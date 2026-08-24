package com.hcs.minions.work.slayer;

import org.bukkit.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 仆从击杀标记：猎魔仆从用 {@code damage()} 击杀怪物以触发 EntityDeathEvent（兼容任务/统计类插件），
 * 但掉落由仆从模拟 loot 表给出，不能叠加原版自然掉落 —— 被标记的实体死亡时清空自然掉落与经验。
 */
public final class SlayerKills {

    private static final Set<UUID> MARKED = ConcurrentHashMap.newKeySet();

    private SlayerKills() {
    }

    public static void mark(Entity entity) {
        MARKED.add(entity.getUniqueId());
    }

    /** 消费标记：实体确为仆从击杀返回 true（标记随即移除，不残留）。 */
    public static boolean consume(Entity entity) {
        return MARKED.remove(entity.getUniqueId());
    }

    /** 伤害未致死时撤销标记，避免后续他人击杀被误吞掉落。 */
    public static void unmark(Entity entity) {
        MARKED.remove(entity.getUniqueId());
    }
}
