package com.hcs.minions;

import com.hcs.minions.command.MinionCommand;
import com.hcs.minions.command.MinionsCommand;
import com.hcs.minions.config.ConfigLoader;
import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.core.ServiceRegistry;
import com.hcs.minions.gui.CollectionGui;
import com.hcs.minions.gui.CollectionGuiListener;
import com.hcs.minions.gui.FuelGuiListener;
import com.hcs.minions.gui.MinionGUIListener;
import com.hcs.minions.gui.UpgradeCraftGuiListener;
import com.hcs.minions.listener.MinionInteractionListener;
import com.hcs.minions.listener.SlayerDeathListener;
import com.hcs.minions.repository.MinionRepository;
import com.hcs.minions.repository.RepositoryFactory;
import com.hcs.minions.service.BlockSearcher;
import com.hcs.minions.service.CollectionService;
import com.hcs.minions.service.EconomyService;
import com.hcs.minions.service.MinionEntityService;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.service.OfflineSettlement;
import com.hcs.minions.service.PermissionService;
import com.hcs.minions.service.SellService;
import com.hcs.minions.service.hook.SkyblockHook;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.AsyncExecutor;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.Logs;
import com.hcs.minions.util.Messages;
import com.hcs.minions.work.MinionWorkStrategy;
import com.hcs.minions.work.WorkStrategyRegistry;
import com.hcs.minions.work.farmer.FarmerStrategy;
import com.hcs.minions.work.fisher.FisherStrategy;
import com.hcs.minions.work.generator.GeneratorStrategy;
import com.hcs.minions.work.lumberjack.LumberjackStrategy;
import com.hcs.minions.work.miner.MinerStrategy;
import com.hcs.minions.work.rancher.RancherStrategy;
import com.hcs.minions.work.slayer.SlayerStrategy;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 主类：只负责生命周期管理与服务装配，严禁业务逻辑。
 */
public final class MinionsPlugin extends JavaPlugin {

    private final ServiceRegistry registry = new ServiceRegistry();

    @Override
    public void onEnable() {
        Messages.load(this);
        GuiText.load(this);

        PluginConfig config = ConfigLoader.load(this);
        ConfigProvider configProvider = new ConfigProvider(this, config);
        registry.setConfig(config);

        AsyncExecutor async = new AsyncExecutor();
        registry.setAsync(async);

        MinionRepository repository = RepositoryFactory.create(config.database(), async, getDataFolder());
        registry.setRepository(repository);

        EconomyService economy = new EconomyService(this, configProvider, async);
        registry.setEconomy(economy);
        SkyblockHook skyblock = new SkyblockHook(this);
        registry.setSkyblock(skyblock);

        // 策略不持有配置快照：类型配置经 WorkContext 实时注入，/minion reload 即时生效
        List<MinionWorkStrategy> strategyList = new ArrayList<>();
        strategyList.add(new MinerStrategy());
        strategyList.add(new FarmerStrategy());
        strategyList.add(new LumberjackStrategy());
        strategyList.add(new FisherStrategy());
        strategyList.add(new SlayerStrategy());
        strategyList.add(new RancherStrategy());
        strategyList.add(new GeneratorStrategy());
        WorkStrategyRegistry strategies = new WorkStrategyRegistry(strategyList);
        registry.setStrategies(strategies);

        BlockSearcher searcher = new BlockSearcher(config.maxChecksPerCycle());
        registry.setSearcher(searcher);

        MinionEntityService entities = new MinionEntityService(this, configProvider);
        registry.setEntities(entities);

        SellService sell = new SellService(economy, async, configProvider, this);
        registry.setSell(sell);

        UpgradeService upgrades = new UpgradeService(this);
        registry.setUpgrades(upgrades);

        CollectionService collection = new CollectionService(this, configProvider, economy);
        collection.load();
        registry.setCollection(collection);

        PermissionService permissions = new PermissionService(configProvider, collection);

        MinionManager manager = new MinionManager(this, configProvider, repository, strategies, searcher, entities, sell, skyblock, permissions, async, upgrades, collection);
        registry.setManager(manager);

        MinionItemService itemService = new MinionItemService(this, configProvider);
        registry.setItemService(itemService);

        CollectionGui collectionGui = new CollectionGui(configProvider, manager, collection, permissions);
        getServer().getPluginManager().registerEvents(new MinionGUIListener(manager, itemService, entities, configProvider, upgrades, skyblock), this);
        getServer().getPluginManager().registerEvents(new MinionInteractionListener(manager, itemService, entities, permissions, collection, skyblock, configProvider), this);
        getServer().getPluginManager().registerEvents(new CollectionGuiListener(collectionGui, manager, configProvider, itemService), this);
        getServer().getPluginManager().registerEvents(new FuelGuiListener(manager, configProvider), this);
        getServer().getPluginManager().registerEvents(
                new UpgradeCraftGuiListener(this, manager, itemService, configProvider, skyblock), this);
        getServer().getPluginManager().registerEvents(new SlayerDeathListener(), this);

        getServer().getCommandMap().register("skyminions", new MinionCommand(itemService, manager, upgrades, configProvider, this));
        getServer().getCommandMap().register("skyminions", new MinionsCommand(collectionGui));

        // 离线收益结算：主人上线时按三道平衡锁补发闲置窗产出
        getServer().getPluginManager().registerEvents(
                new OfflineSettlement(this, configProvider, manager, strategies::get, collection), this);

        // Collection 定期落盘（60 秒）
        async.scheduleAtFixedRate(collection::save, 60, 60, java.util.concurrent.TimeUnit.SECONDS);

        manager.start();
        Logs.info("SkyMinions 已启用");
    }

    @Override
    public void onDisable() {
        // 关闭顺序：先停调度与 GUI -> 等待在途异步任务收尾（避免打断落库）
        // -> 落盘 collection.yml -> 冲刷并关闭仓库
        registry.manager().stop();
        registry.async().close();
        registry.collection().save();
        registry.repository().close();
        Logs.info("SkyMinions 已关闭");
    }
}
