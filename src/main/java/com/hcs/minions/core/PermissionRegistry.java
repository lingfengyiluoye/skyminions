package com.hcs.minions.core;

import com.hcs.minions.model.MinionType;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限注册表。
 *
 * <p>Paper 的 paper-plugin.yml <b>不支持</b> permissions 段（与 commands 同限），
 * 写在 yml 里的声明会被静默忽略 —— 未注册的节点虽然 {@code hasPermission()} 仍可判定，
 * 但不会出现在 LuckPerms 的 /lp tree 与网页编辑器中，默认值也不生效。</p>
 *
 * <p>因此组合根在 onEnable 时调用 {@link #registerAll()} 程序化注册全部节点，
 * 让 LuckPerms / 其他权限插件能正常枚举并继承默认值。</p>
 */
public final class PermissionRegistry {

    /** 数量上限权限前缀（minions.limit.<n> 为动态数字节点，不逐一注册）。 */
    public static final String ADMIN = "minions.admin";
    public static final String USE = "minions.use";

    private PermissionRegistry() {
    }

    /** 构造全部权限定义（在类型注册表装载完成后调用）。 */
    public static List<Permission> buildAll() {
        List<Permission> out = new ArrayList<>();
        out.add(new Permission(ADMIN, "仆从系统管理权限", PermissionDefault.OP));
        out.add(new Permission(USE, "使用仆从", PermissionDefault.TRUE));
        // 类型使用权限：key 即中文名（如 煤矿仆从）
        for (MinionType type : MinionType.all()) {
            if (MinionType.fallback().equals(type)) {
                continue; // fallback 占位不注册
            }
            out.add(new Permission("minions.type." + type.key(),
                    "使用「" + type.displayName() + "」", PermissionDefault.TRUE));
        }
        return out;
    }

    /** 向 Bukkit 注册全部权限（已存在的自动跳过，幂等可重复调用）。 */
    public static void registerAll() {
        for (Permission permission : buildAll()) {
            try {
                Bukkit.getPluginManager().addPermission(permission);
            } catch (IllegalArgumentException ignored) {
                // 已注册过（如 /minion reload 重入）：保留原注册即可，非异常场景
            }
        }
    }
}
