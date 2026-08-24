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
 * @param offline           离线收益配置
 * @param render            渲染（Display Entity）配置
 * @param tickPeriod        全局调度周期（tick）
 * @param maxChecksPerCycle 单次策略执行方块检查硬上限（强制 < 50）
 * @param maxMinionsPerPlayer 每玩家仆从数量上限
 * @param types             每种仆从的独立配置（key -> MinionTypeConfig）
 * @param collections       Collection 里程碑配置
 * @param upgradeRequirePreviousBody 升级是否额外消耗 1 个「当前等级的仆从生成物」本体（对齐文档玩法）
 * @param collectionUnlockEnabled    是否启用收集解锁（收集 N 资源才解锁对应仆从类型）
 */
public record PluginConfig(
        DatabaseConfig database,
        EconomyConfig economy,
        OfflineConfig offline,
        RenderConfig render,
        long tickPeriod,
        int maxChecksPerCycle,
        int maxMinionsPerPlayer,
        String headTexture,
        boolean debug,
        Map<String, MinionTypeConfig> types,
        CollectionConfig collections,
        boolean upgradeRequirePreviousBody,
        boolean collectionUnlockEnabled
) {

    public PluginConfig {
        types = Map.copyOf(types); // 快照不可变
        if (maxChecksPerCycle >= 50) {
            throw new IllegalArgumentException("max-checks-per-cycle 必须 < 50");
        }
    }

    public MinionTypeConfig type(MinionType type) {
        return types.get(type.key());
    }
}
