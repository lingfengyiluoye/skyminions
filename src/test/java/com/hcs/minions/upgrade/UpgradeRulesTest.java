package com.hcs.minions.upgrade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link UpgradeRules} 合成升级规则（本体需求判定）边界测试。 */
class UpgradeRulesTest {

    @Test
    void middleLevelsNeedBodyWhenSwitchOn() {
        // 常规升级路径：等级 1..maxLevel-1 均需当前等级本体
        assertTrue(UpgradeRules.needsPreviousBody(1, 12, true));
        assertTrue(UpgradeRules.needsPreviousBody(11, 12, true));
    }

    @Test
    void globalSwitchOffDisablesBody() {
        // 服主关闭开关后回退为"纯材料"升级
        assertFalse(UpgradeRules.needsPreviousBody(1, 12, false));
        assertFalse(UpgradeRules.needsPreviousBody(11, 12, false));
    }

    @Test
    void maxLevelNeverNeedsBody() {
        // 满级不可再升，自然不需要本体
        assertFalse(UpgradeRules.needsPreviousBody(12, 12, true));
    }

    @Test
    void invalidLevelNeverNeedsBody() {
        // 非法等级（<1）防御性拒绝
        assertFalse(UpgradeRules.needsPreviousBody(0, 12, true));
        assertFalse(UpgradeRules.needsPreviousBody(-1, 12, true));
    }

    @Test
    void joinMissingUsesChineseSeparator() {
        assertEquals("红石 ×8、煤炭 ×16", UpgradeRules.joinMissing(List.of("红石 ×8", "煤炭 ×16")));
        assertEquals("", UpgradeRules.joinMissing(List.of()));
    }
}
