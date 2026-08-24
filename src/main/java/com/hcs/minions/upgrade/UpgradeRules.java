package com.hcs.minions.upgrade;

/**
 * 合成升级规则（纯函数，可单测）：升级 Tier 需要「上一级 Minion 本体 + 材料」。
 *
 * <p>约定：从等级 n 升到 n+1 时，需额外消耗 1 个「等级 n 的仆从生成物」
 * （即当前等级本体，对应文档的"上一级 Minion"），材料照常从仆从仓库扣除。</p>
 */
public final class UpgradeRules {

    private UpgradeRules() {
    }

    /**
     * 本次升级是否需要消耗仆从本体。
     *
     * @param level        当前等级（>=1）
     * @param maxLevel     类型最大等级
     * @param globalSwitch 全局开关（config: upgrade-require-previous-body）
     * @return true = 需 1 个当前等级的仆从生成物
     */
    public static boolean needsPreviousBody(int level, int maxLevel, boolean globalSwitch) {
        return globalSwitch && level >= 1 && level < maxLevel;
    }

    /**
     * 缺失清单拼接（"红石 ×8、煤炭 ×16" 风格）：供升级失败提示复用。
     */
    public static String joinMissing(Iterable<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
