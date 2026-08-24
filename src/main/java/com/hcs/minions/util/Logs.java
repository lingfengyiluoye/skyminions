package com.hcs.minions.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一日志出口。全部日志走 SLF4J（Paper 平台已暴露 slf4j-api），
 * 严禁在业务代码中空 catch 吞异常 —— 捕获后必须记录并优雅降级（例如返还物品）。
 */
public final class Logs {
    private static final Logger LOG = LoggerFactory.getLogger("SkyMinions");

    private Logs() {
    }

    public static Logger log() {
        return LOG;
    }

    public static void info(String msg, Object... args) {
        LOG.info(msg, args);
    }

    public static void warn(String msg, Object... args) {
        LOG.warn(msg, args);
    }

    /** slf4j 自动识别末尾的 Throwable 参数；无占位符时原样输出。 */
    public static void error(String msg, Object... args) {
        LOG.error(msg, args);
    }
}
