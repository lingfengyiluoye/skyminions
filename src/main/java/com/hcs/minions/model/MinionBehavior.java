package com.hcs.minions.model;

/**
 * 行为原型（7 个，一一对应工作策略实现）。
 * 「行为」回答"怎么干活"，与「类型」身份（{@link MinionType}，配置驱动、可无限扩展）正交：
 * 例如 煤矿/铁矿/钻石… 全部复用 MINING 行为，仅产物/目标/曲线不同。
 */
public enum MinionBehavior {
    /** 统计挖掘：只读方块类型，摆什么产什么。 */
    MINING,
    /** 统计农耕：成熟判定 + 自动开垦播种。 */
    FARMING,
    /** 真实连锁伐木 + 树根补种。 */
    FORAGING,
    /** 钓鱼模拟产出。 */
    FISHING,
    /** 猎魔：真实击杀优先，无怪模拟回退。 */
    COMBAT,
    /** 畜牧模拟：战利品表 roll。 */
    RANCHING,
    /** 圆石生成循环（生成→采集）。 */
    GENERATOR
}
