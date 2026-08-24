package com.hcs.minions.core;

import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.repository.MinionRepository;
import com.hcs.minions.service.BlockSearcher;
import com.hcs.minions.service.CollectionService;
import com.hcs.minions.service.EconomyService;
import com.hcs.minions.service.MinionEntityService;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.SellService;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.work.WorkStrategyRegistry;

/**
 * 服务注册表（组合根）。主类在 onEnable 中按依赖顺序装配一次，此后全局只读共享。
 */
public final class ServiceRegistry {

    private AsyncExecutor async;
    private PluginConfig config;
    private MinionRepository repository;
    private EconomyService economy;
    private SkyblockHook skyblock;
    private WorkStrategyRegistry strategies;
    private BlockSearcher searcher;
    private MinionEntityService entities;
    private SellService sell;
    private MinionManager manager;
    private MinionItemService itemService;
    private UpgradeService upgrades;
    private CollectionService collection;

    public AsyncExecutor async() {
        return async;
    }

    public void setAsync(AsyncExecutor async) {
        this.async = async;
    }

    public PluginConfig config() {
        return config;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public MinionRepository repository() {
        return repository;
    }

    public void setRepository(MinionRepository repository) {
        this.repository = repository;
    }

    public EconomyService economy() {
        return economy;
    }

    public void setEconomy(EconomyService economy) {
        this.economy = economy;
    }

    public SkyblockHook skyblock() {
        return skyblock;
    }

    public void setSkyblock(SkyblockHook skyblock) {
        this.skyblock = skyblock;
    }

    public WorkStrategyRegistry strategies() {
        return strategies;
    }

    public void setStrategies(WorkStrategyRegistry strategies) {
        this.strategies = strategies;
    }

    public BlockSearcher searcher() {
        return searcher;
    }

    public void setSearcher(BlockSearcher searcher) {
        this.searcher = searcher;
    }

    public MinionEntityService entities() {
        return entities;
    }

    public void setEntities(MinionEntityService entities) {
        this.entities = entities;
    }

    public SellService sell() {
        return sell;
    }

    public void setSell(SellService sell) {
        this.sell = sell;
    }

    public MinionManager manager() {
        return manager;
    }

    public void setManager(MinionManager manager) {
        this.manager = manager;
    }

    public MinionItemService itemService() {
        return itemService;
    }

    public void setItemService(MinionItemService itemService) {
        this.itemService = itemService;
    }

    public UpgradeService upgrades() {
        return upgrades;
    }

    public void setUpgrades(UpgradeService upgrades) {
        this.upgrades = upgrades;
    }

    public CollectionService collection() {
        return collection;
    }

    public void setCollection(CollectionService collection) {
        this.collection = collection;
    }
}
