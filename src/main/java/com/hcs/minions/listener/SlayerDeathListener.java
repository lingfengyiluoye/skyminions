package com.hcs.minions.listener;

import com.hcs.minions.work.slayer.SlayerKills;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * 猎魔仆从击杀联动：仆从以 damage() 击杀怪物会触发正常死亡流程，
 * 这里清掉被标记实体的自然掉落与经验（掉落已由仆从模拟 loot 表结算，避免双份）。
 */
public final class SlayerDeathListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDeath(EntityDeathEvent event) {
        if (SlayerKills.consume(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }
}
