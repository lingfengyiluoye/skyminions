package com.hcs.minions.work;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 方块破坏与掉落采集的工具。
 * 必须在主线程/区域线程调用。
 */
public final class BlockOps {

    private BlockOps() {
    }

    /**
     * 计算掉落并移除方块（不生成世界掉落物实体，掉落由调用方收集）。
     *
     * <p>必须 {@code applyPhysics=true}：刷石机、水流农场等依赖方块物理更新的
     * 机制，只有在破坏方块时触发邻居更新，水/岩浆才会重新流动、重新接触生成新方块。
     * 旧的 {@code applyPhysics=false} 会抑制邻居更新，导致矿工挖掉石头后水岩浆不
     * 再流动、刷石机"卡死"（用手撸则正常，因为玩家破坏默认触发更新）。</p>
     */
    public static List<ItemStack> breakAndCollect(Block block) {
        Collection<ItemStack> drops = block.getDrops(null, null);
        List<ItemStack> out = new ArrayList<>(drops.size());
        for (ItemStack drop : drops) {
            if (drop != null && drop.getType() != Material.AIR && drop.getAmount() > 0) {
                out.add(drop);
            }
        }
        // applyPhysics=true：触发邻居方块更新，保证刷石机等机制正常刷新
        block.setType(Material.AIR, true);
        return out;
    }
}
