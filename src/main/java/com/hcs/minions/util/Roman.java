package com.hcs.minions.util;

/**
 * 罗马数字转换（对齐 Hypixel Tier 显示：Coal Minion IV）。
 * 纯函数、无 Bukkit 依赖，可直接单测。
 */
public final class Roman {

    private static final int[] VALUES = {
            1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1
    };
    private static final String[] SYMBOLS = {
            "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"
    };

    private Roman() {
    }

    /** 整数转罗马数字（1~3999）；越界输入回退为阿拉伯数字字符串，绝不抛异常。 */
    public static String of(int value) {
        if (value < 1 || value > 3999) {
            return String.valueOf(value);
        }
        StringBuilder sb = new StringBuilder();
        int rest = value;
        for (int i = 0; i < VALUES.length; i++) {
            while (rest >= VALUES[i]) {
                sb.append(SYMBOLS[i]);
                rest -= VALUES[i];
            }
        }
        return sb.toString();
    }
}
