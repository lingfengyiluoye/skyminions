package com.hcs.minions.service;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.Logs;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 经济服务（Vault，可选）。
 *
 * <p>金额内部使用 long（分）或 BigDecimal 运算，仅在 Vault 边界转换为 double。
 * 所有 Vault 调用在虚拟线程异步执行；成功后必须校验
 * {@code EconomyResponse.type == SUCCESS}，否则不结算、不清空背包。
 */
public final class EconomyService {

    private final Economy economy;
    private final AsyncExecutor async;
    private final ConfigProvider config;
    private final JavaPlugin plugin;

    public EconomyService(JavaPlugin plugin, ConfigProvider config, AsyncExecutor async) {
        this.plugin = plugin;
        this.config = config;
        this.async = async;
        // Vault 注册一次即可（经济插件不会随 /minion reload 变化）；
        // 价格倍率等数值参数经 config 实时读取，热重载即时生效
        this.economy = setupEconomy(config.get());
    }

    private Economy setupEconomy(com.hcs.minions.config.PluginConfig cfg) {
        if (!cfg.economy().enabled() || !Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            Logs.warn("Vault 未启用，经济功能关闭");
            return null;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            Logs.warn("未找到 Vault Economy 实现，经济功能关闭");
            return null;
        }
        return rsp.getProvider();
    }

    public boolean isEnabled() {
        return economy != null;
    }

    /**
     * 计算售卖价格（分）。单位价 × 数量 × 全局倍率，用 BigDecimal 避免浮点误差。
     */
    public long priceCents(long units, double pricePerUnit) {
        return BigDecimal.valueOf(units)
                .multiply(BigDecimal.valueOf(pricePerUnit))
                .multiply(BigDecimal.valueOf(config.get().economy().priceMultiplier()))
                .multiply(BigDecimal.valueOf(100))
                .longValue();
    }

    /**
     * 异步向玩家账户加款。返回是否成功（responseType == SUCCESS）。
     */
    public CompletableFuture<Boolean> depositCents(UUID playerId, long cents) {
        if (economy == null || cents <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return async.submit(() -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(playerId);
            if (target.getName() == null) {
                return false;
            }
            EconomyResponse response = economy.depositPlayer(target, cents / 100.0);
            if (response == null || response.type != EconomyResponse.ResponseType.SUCCESS) {
                Logs.warn("Vault 加款失败: player={}, amount={}, type={}",
                        playerId, cents, response == null ? "null" : response.type);
                return false;
            }
            return true;
        });
    }
}
