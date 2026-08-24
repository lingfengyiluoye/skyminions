package com.hcs.minions;

import com.hcs.minions.command.MinionCommand;
import com.hcs.minions.command.MinionsCommand;
import com.hcs.minions.config.ConfigLoader;
import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.config.MinionTypeConfig;
import com.hcs.minions.config.PluginConfig;
import com.hcs.minions.core.ServiceRegistry;
import com.hcs.minions.gui.CollectionGui;
import com.hcs.minions.gui.CollectionGuiListener;
import com.hcs.minions.gui.FuelGuiListener;
import com.hcs.minions.gui.MinionGUIListener;
import com.hcs.minions.gui.UpgradeCraftGuiListener;
import com.hcs.minions.listener.MinionInteractionListener;
import com.hcs.minions.listener.SlayerDeathListener;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.repository.MinionRepository;
import com.hcs.minions.repository.RepositoryFactory;
import com.hcs.minions.service.BlockSearcher;
import com.hcs.minions.service.CollectionService;
import com.hcs.minions.service.EconomyService;
import com.hcs.minions.service.MinionEntityService;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
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
import java.util.function.Function;

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

        MinionRepository repository = RepositoryFactory.create(config.database(), async, config, getDataFolder());
        registry.setRepository(repository);

        EconomyService economy = new EconomyService(this, config.economy(), async);
        registry.setEconomy(economy);
        SkyblockHook skyblock = new SkyblockHook(this);
        registry.setSkyblock(skyblock);

        List<MinionWorkStrategy> strategyList = new ArrayList<>();
        addStrategy(strategyList, config, MinionType.MINER, MinerStrategy::new);
        addStrategy(strategyList, config, MinionType.FARMER, FarmerStrategy::new);
        addStrategy(strategyList, config, MinionType.LUMBERJACK, LumberjackStrategy::new);
        addStrategy(strategyList, config, MinionType.FISHER, FisherStrategy::new);
        addStrategy(strategyList, config, MinionType.SLAYER, SlayerStrategy::new);
        addStrategy(strategyList, config, MinionType.RANCHER, RancherStrategy::new);
        addStrategy(strategyList, config, MinionType.COBBLE, GeneratorStrategy::new);
        WorkStrategyRegistry strategies = new WorkStrategyRegistry(strategyList);
        registry.setStrategies(strategies);

        BlockSearcher searcher = new BlockSearcher(config.maxChecksPerCycle());
        registry.setSearcher(searcher);

        MinionEntityService entities = new MinionEntityService(this, config);
        registry.setEntities(entities);

        SellService sell = new SellService(economy, async, configProvider, this);
        registry.setSell(sell);

        UpgradeService upgrades = new UpgradeService(this);
        registry.setUpgrades(upgrades);

        CollectionService collection = new CollectionService(this, config.collections(), economy);
        collection.load();
        registry.setCollection(collection);

        PermissionService permissions = new PermissionService(config, collection);

        MinionManager manager = new MinionManager(this, configProvider, repository, strategies, searcher, entities, sell, skyblock, permissions, async, upgrades, collection);
        registry.setManager(manager);

        MinionItemService itemService = new MinionItemService(this, config);
        registry.setItemService(itemService);

        CollectionGui collectionGui = new CollectionGui(config, manager, collection, permissions);
        getServer().getPluginManager().registerEvents(new MinionGUIListener(manager, itemService, entities, config, upgrades, skyblock), this);
        getServer().getPluginManager().registerEvents(new MinionInteractionListener(manager, itemService, entities, permissions, collection, skyblock, config), this);
        getServer().getPluginManager().registerEvents(new CollectionGuiListener(collectionGui), this);
        getServer().getPluginManager().registerEvents(new FuelGuiListener(manager, config), this);
        getServer().getPluginManager().registerEvents(
                new UpgradeCraftGuiListener(this, manager, itemService, config, skyblock), this);
        getServer().getPluginManager().registerEvents(new SlayerDeathListener(), this);

        getServer().getCommandMap().register("skyminions", new MinionCommand(itemService, manager, upgrades, configProvider, this));
        getServer().getCommandMap().register("skyminions", new MinionsCommand(collectionGui));

        // Collection 定期落盘（60 秒）
        async.scheduleAtFixedRate(collection::save, 60, 60, java.util.concurrent.TimeUnit.SECONDS);

        manager.start();
        Logs.info("SkyMinions 已启用");
    }

    private static void addStrategy(List<MinionWorkStrategy> out, PluginConfig config,
                                    MinionType type, Function<MinionTypeConfig, MinionWorkStrategy> factory) {
        MinionTypeConfig cfg = config.type(type);
        if (cfg != null) {
            out.add(factory.apply(cfg));
        } else {
            Logs.warn("配置缺少仆从类型 {}，已跳过其策略注册", type.key());
        }
    }

    @Override
    public void onDisable() {
        registry.manager().stop();
        registry.collection().save();
        registry.repository().close();
        registry.async().close();
        Logs.info("SkyMinions 已关闭");
    }
}
