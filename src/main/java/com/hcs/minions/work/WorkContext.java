package com.hcs.minions.work;

import com.hcs.minions.model.Minion;
import com.hcs.minions.service.BlockSearcher;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

/**
 * 单次工作周期的上下文。策略只依赖此对象，绝不反向引用 Manager 或插件主类。
 */
public record WorkContext(
        Minion minion,
        World world,
        Block anchor,
        int radius,
        Random random,
        BlockSearcher searcher
) {
}
