package com.hcs.minions.config;

import com.hcs.minions.model.MinionType;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局配置根对象：启动时由 ConfigLoader 一次性反序列化为不可变快照，全局共享只读。
 * 严禁任何业务代码调用 getConfig()；需要配置时通过 ServiceRegistry.config() 取此对象。
 *
 * @param database          数据库配置
 * @param economy           经济/售卖配置
 * @param render            渲染（Display Entity）配置
 * @param tickPeriod        全局调度周期（tick）
 * @param maxChecksPerCycle 单次策略执行方块检查硬上限（强制 < 50）
 * @param maxMinionsPerPlayer 每玩家仆从数量上限
 * @param types             每种仆从的独立配置（key -> MinionTypeConfig）
 * @param collections       Collection 里程碑配置
 * @param upgradeRequirePreviousBody 升级是否额外消耗 1 个「当前等级的仆从生成物」本体（对齐文档玩法）
 * @param collectionUnlockEnabled    是否启用收集解锁（收集 N 资源才解锁对应仆从类型）
 * @param playerScanRadius           玩家活动半径（方块）：半径内无玩家则仆从休眠，对齐 Hypixel
 * @param minPlacementDistance       仆从间最小切比雪夫距离（同世界水平面）；0 = 不限制。
 *                                   默认 5 = 两个 5x5 工作区恰好不重叠
 * @param rareDropBroadcast          稀有掉落是否全服广播（false 时仅通知主人）
 */
public record PluginConfig(
        DatabaseConfig database,
        EconomyConfig economy,
        RenderConfig render,
        long tickPeriod,
        int maxChecksPerCycle,
        int maxMinionsPerPlayer,
        String headTexture,
        boolean debug,
        Map<String, MinionTypeConfig> types,
        CollectionConfig collections,
        OfflineProductionConfig offlineProduction,
        boolean upgradeRequirePreviousBody,
        boolean collectionUnlockEnabled,
        double playerScanRadius,
        int minPlacementDistance,
        boolean rareDropBroadcast
) {

    public PluginConfig {
        types = types == null ? Map.of() : Map.copyOf(types); // 快照不可变
        tickPeriod = Math.max(1L, tickPeriod);
        if (maxChecksPerCycle >= 50) {
            throw new IllegalArgumentException("max-checks-per-cycle 必须 < 50");
        }
        if (maxChecksPerCycle < 1) {
            maxChecksPerCycle = 1;
        }
        if (maxMinionsPerPlayer < 0) {
            maxMinionsPerPlayer = 0;
        }
        if (!Double.isFinite(playerScanRadius) || playerScanRadius < 0) {
            playerScanRadius = 0; // 0 = 永远不休眠
        }
        if (minPlacementDistance < 0) {
            minPlacementDistance = 0;
        }
    }

    public MinionTypeConfig type(MinionType type) {
        return types.get(type.key());
    }
}
