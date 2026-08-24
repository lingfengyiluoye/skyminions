package com.hcs.minions.model;

/**
 * 仆从图鉴分类（对齐 Hypixel Collection 的分类页签）。
 * 图鉴顶行按分类过滤卡片列表。
 */
public enum MinionCategory {
    MINING("采矿"),
    FARMING("农业"),
    FORAGING("伐木"),
    COMBAT("战斗"),
    FISHING("钓鱼"),
    SPECIAL("特殊");

    private final String displayName;

    MinionCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
