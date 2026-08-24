package com.hcs.minions.service;

import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.model.MinionType;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * 权限服务：与 LuckPerms 联动。LuckPerms 会把自己注册为 Bukkit 权限提供方，
 * 因此 {@code Player#hasPermission} 即自动映射 LP 的组/权限 —— 无需硬依赖 LP。
 *
 * <p>可授予的权限（在 LuckPerms 里给组或玩家）：
 * <ul>
 *   <li>{@code minions.admin} —— 管理权限</li>
 *   <li>{@code minions.type.<type>} —— 能否使用某类仆从</li>
 *   <li>{@code minions.limit.<n>} —— 仆从数量上限（取拥有的最大 n，默认用 config）</li>
 * </ul>
 */
public final class PermissionService {

    /** 仆从数量上限权限前缀：minions.limit.<n> */
    private static final String LIMIT_PREFIX = "minions.limit.";

    /** 数量上限权限支持的最大值（含 minions.limit.* 通配）。 */
    private static final int MAX_SCAN = 512;

    private final ConfigProvider config;
    private final CollectionService collection;

    public PermissionService(ConfigProvider config, CollectionService collection) {
        this.config = config;
        this.collection = collection;
    }

    public boolean isAdmin(Player player) {
        return player.hasPermission("minions.admin");
    }

    public boolean canUseType(Player player, MinionType type) {
        if (isAdmin(player)) {
            return true;
        }
        if (!player.hasPermission("minions.type." + type.key())) {
            return false;
        }
        return isUnlocked(player, type);
    }

    /**
     * 收集解锁判断（对齐文档：收集 N 资源才解锁类型）：
     * 全局开关关闭或未配置 unlock-amount 时直接放行；
     * 否则要求该玩家产物（product）累计收集量达标。管理员在 {@link #canUseType} 已提前放行。
     */
    public boolean isUnlocked(Player player, MinionType type) {
        if (!config.get().collectionUnlockEnabled()) {
            return true;
        }
        MinionTypeConfig cfg = config.get().type(type);
        if (cfg == null || !cfg.hasUnlockRequirement()) {
            return true;
        }
        return collection.get(player.getUniqueId(), cfg.product()) >= cfg.unlockAmount();
    }

    /**
     * 玩家仆从数量上限：从生效权限集合中一次性解析 {@code minions.limit.<n>} 的最大 n，
     * 支持 {@code minions.limit.*} 通配；未授予则回退到配置默认值。
     *
     * <p>旧实现逐 n 调用 {@code hasPermission}（1~512 次），本实现改为单次遍历
     * {@code Player#getEffectivePermissions()}，对 LuckPerms 同样有效 —— LP 会把自己注册为
     * Bukkit 权限提供方，其（含继承）权限会出现在生效权限集合中。
     */
    public int maxMinions(Player player) {
        int best = config.get().maxMinionsPerPlayer();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue; // 显式拒绝的权限不参与计算
            }
            String perm = info.getPermission();
            if (perm == null || !perm.regionMatches(true, 0, LIMIT_PREFIX, 0, LIMIT_PREFIX.length())) {
                continue;
            }
            String suffix = perm.substring(LIMIT_PREFIX.length());
            if (suffix.equalsIgnoreCase("*")) {
                return MAX_SCAN;
            }
            try {
                int n = Integer.parseInt(suffix);
                if (n > best) {
                    best = n;
                }
            } catch (NumberFormatException ignored) {
                // 非数字后缀（如 minions.limit.vip）忽略
            }
        }
        return best;
    }
}
