package com.hcs.minions.hook;

import com.hcs.minions.model.MinionType;
import com.hcs.minions.service.CollectionService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.PermissionService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI 占位符扩展（可选集成：未安装 PAPI 时不会注册，插件功能不受影响）。
 *
 * <p>可用占位符（前缀 {@code skyminions_}）：</p>
 * <pre>
 * %skyminions_count%                 该玩家当前仆从数
 * %skyminions_limit%                 该玩家仆从上限（权限 + 里程碑加成）
 * %skyminions_count_&lt;type&gt;%         指定类型已放置数（如 %skyminions_count_coal%）
 * %skyminions_produced_&lt;type&gt;%      指定类型全场累计产出件数
 * %skyminions_collection_&lt;MAT&gt;%     指定资源收集量（如 %skyminions_collection_COAL%）
 * %skyminions_progress%              图鉴进度（已解锁/总数）
 * %skyminions_bonus_slots%           里程碑槽位加成
 * </pre>
 *
 * <p>全部为只读查询，任意线程可调；参数缺失或类型未知时返回空串（记分板不显示而非显示 0）。</p>
 */
public final class MinionsPlaceholderExpansion extends PlaceholderExpansion {

    private final MinionManager manager;
    private final CollectionService collection;
    private final PermissionService permissions;

    public MinionsPlaceholderExpansion(MinionManager manager, CollectionService collection,
                                       PermissionService permissions) {
        this.manager = manager;
        this.collection = collection;
        this.permissions = permissions;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "skyminions";
    }

    @Override
    public @NotNull String getAuthor() {
        return "hcs";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true; // 插件重载后占位符仍然可用
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        java.util.UUID id = player.getUniqueId();
        // 在线专属（上限计算依赖生效权限）
        Player online = player instanceof Player p ? p : null;
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "count":
                return String.valueOf(manager.countByOwner(id));
            case "limit":
                return online == null ? "" : String.valueOf(
                        permissions.maxMinions(online) + collection.bonusSlots(id));
            case "bonus_slots":
                return String.valueOf(collection.bonusSlots(id));
            case "progress":
                int unlocked = 0;
                for (MinionType t : MinionType.all()) {
                    if (online != null && permissions.isUnlocked(online, t)) {
                        unlocked++;
                    }
                }
                return unlocked + "/" + MinionType.all().size();
            default:
                break;
        }
        if (params.toLowerCase(java.util.Locale.ROOT).startsWith("count_")) {
            return String.valueOf(countOfType(id, params.substring(6)));
        }
        if (params.toLowerCase(java.util.Locale.ROOT).startsWith("produced_")) {
            return String.valueOf(producedOfType(id, params.substring(9)));
        }
        if (params.toLowerCase(java.util.Locale.ROOT).startsWith("collection_")) {
            return String.valueOf(collectionOf(id, params.substring(11)));
        }
        return "";
    }

    private long countOfType(java.util.UUID owner, String typeKey) {
        MinionType type = MinionType.fromKey(typeKey).orElse(null);
        if (type == null) {
            return 0;
        }
        return manager.all().stream()
                .filter(m -> owner.equals(m.owner()) && m.type() == type)
                .count();
    }

    private long producedOfType(java.util.UUID owner, String typeKey) {
        MinionType type = MinionType.fromKey(typeKey).orElse(null);
        if (type == null) {
            return 0;
        }
        return manager.all().stream()
                .filter(m -> owner.equals(m.owner()) && m.type() == type)
                .mapToLong(com.hcs.minions.model.Minion::totalProduced)
                .sum();
    }

    private long collectionOf(java.util.UUID owner, String materialName) {
        try {
            org.bukkit.Material mat = org.bukkit.Material.valueOf(materialName.toUpperCase(java.util.Locale.ROOT));
            return collection.get(owner, mat);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
