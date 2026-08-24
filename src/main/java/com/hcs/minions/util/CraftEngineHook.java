package com.hcs.minions.util;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * CraftEngine 自定义物品软依赖（全反射，无编译期依赖）。
 *
 * <p>服务器上未安装 CraftEngine 时所有方法安全降级（available=false / 返回 null），
 * 插件功能不受影响。自定义物品原型 {@code build(id)} 采用惰性解析 + 缓存：
 * CraftEngine 的物品注册表在自身 enable 完成后才可用，SkyMinions 配置解析时
 * 可能尚未就绪，因此真正构建推迟到首次使用（GUI 展示/扣除匹配）。</p>
 */
public final class CraftEngineHook {

    private static final Method BY_ID;
    private static final Method BUILD_STACK;
    private static final Method GET_CUSTOM_ITEM_ID;

    static {
        Method byId = null;
        Method build = null;
        Method customId = null;
        try {
            Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            byId = api.getMethod("byId", String.class);
            build = byId.getReturnType().getMethod("buildBukkitItem");
            customId = api.getMethod("getCustomItemId", ItemStack.class);
        } catch (Throwable ignored) {
            // CraftEngine 未安装：保持 null，available() = false
        }
        BY_ID = byId;
        BUILD_STACK = build;
        GET_CUSTOM_ITEM_ID = customId;
    }

    private CraftEngineHook() {
    }

    /** CraftEngine 是否存在于类路径。 */
    public static boolean available() {
        return BY_ID != null;
    }

    /**
     * 按 id 构建自定义物品原型（amount=1）。物品未注册或 CraftEngine 未就绪时返回 null。
     * id 支持 {@code namespace:path}（含冒号）或纯 path 两种 CraftEngine 写法。
     */
    public static ItemStack build(String id) {
        if (BY_ID == null || id == null) {
            return null;
        }
        try {
            Object definition = BY_ID.invoke(null, id);
            if (definition == null) {
                return null;
            }
            return (ItemStack) BUILD_STACK.invoke(definition);
        } catch (Throwable t) {
            // 反射失败必须留痕（项目规约：严禁静默吞异常）；每个 id 只告警一次防刷屏
            if (WARNED.add(id)) {
                Logs.warn("CraftEngine 物品 {} 构建失败（未注册或版本不兼容），已按缺失处理", id, t);
            }
            return null;
        }
    }

    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 若 stack 是 CraftEngine 自定义物品返回其 id（namespace:path），否则返回 null。 */
    public static String customItemId(ItemStack stack) {
        if (GET_CUSTOM_ITEM_ID == null || stack == null) {
            return null;
        }
        try {
            Object key = GET_CUSTOM_ITEM_ID.invoke(null, stack);
            return key == null ? null : key.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
