package com.hcs.minions.work;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.model.Minion;
import com.hcs.minions.service.BlockSearcher;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

/**
 * 单次工作周期的上下文。策略只依赖此对象，绝不反向引用 Manager 或插件主类。
 *
 * <p>{@code cfg} 为本次工作时的实时类型配置快照：策略不持有配置引用，
 * 保证 {@code /minion reload} 后冷却/目标/产量上限立即生效。</p>
 */
public record WorkContext(
        Minion minion,
        World world,
        Block anchor,
        MinionTypeConfig cfg,
        int radius,
        Random random,
        BlockSearcher searcher
) {
}
