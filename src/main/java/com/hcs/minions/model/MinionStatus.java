package com.hcs.minions.model;

/**
 * 仆从运行时状态（「它此刻为什么在/不在产出」的唯一真相源）。
 *
 * <p>由 {@code MinionManager#processMinion} 在每个退出点写入，三处消费：</p>
 * <ul>
 *   <li>GUI 信息卡状态行（玩家不打命令也能看到原因）</li>
 *   <li>头顶名牌徽标（▶ 工作 / ⚠ 停工 / ⏾ 休眠）</li>
 *   <li>{@code /minion diagnose} 的诊断结论（两者口径完全一致）</li>
 * </ul>
 */
public enum MinionStatus {

    /** 正常工作中。 */
    WORKING("✅ 运行中", true),
    /** 冷却中（正常等待下一次动作）。 */
    COOLDOWN("冷却中", true),
    /** 扫描半径内无玩家，休眠挂机。 */
    DORMANT("⏾ 休眠中", false),
    /** 仓库满且无售卖出口，停工。 */
    HALTED_FULL("⚠ 仓库已满 · 停工中", false),
    /** 工作范围内没有可用目标（布局问题）。 */
    NO_TARGET("⚠ 范围内无可用目标", false),
    /** 空岛/世界校验不通过。 */
    BLOCKED_BY_ISLAND("⚠ 所在位置不可用", false),
    /** 类型配置缺失（热重载后删了该类型）。 */
    CONFIG_MISSING("⚠ 类型配置缺失", false),
    /** 区块未加载（附近无玩家活动）。 */
    CHUNK_UNLOADED("区块未加载", false);

    private final String label;
    private final boolean producing;

    MinionStatus(String label, boolean producing) {
        this.label = label;
        this.producing = producing;
    }

    /** 中文结论文案（可直接展示）。 */
    public String label() {
        return label;
    }

    /** 是否算「在产出」（冷却中算——那只是正常等待）。 */
    public boolean isProducing() {
        return producing;
    }

    /** 是否需要玩家采取行动（休眠/区块未加载是环境状态，不算异常）。 */
    public boolean needsAttention() {
        return !producing && this != DORMANT && this != CHUNK_UNLOADED;
    }
}
