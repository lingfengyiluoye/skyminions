package com.hcs.minions.command;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.model.MinionSkin;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.upgrade.MinionUpgradeType;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.ItemRef;
import com.hcs.minions.util.Messages;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 管理命令：/minion give | upgrade | skin | reload | purge | list
 *
 * <p>paper-plugin.yml 没有 legacy plugin.yml 的 commands 段，
 * 因此继承 {@link Command} 并通过 {@code Server#getCommandMap()} 注册。
 */
public final class MinionCommand extends Command {

    private final MinionItemService items;
    private final MinionManager manager;
    private final UpgradeService upgrades;
    private final ConfigProvider config;
    private final JavaPlugin plugin;

    public MinionCommand(MinionItemService items, MinionManager manager, UpgradeService upgrades, ConfigProvider config,
                         JavaPlugin plugin) {
        super("minion");
        this.items = items;
        this.manager = manager;
        this.upgrades = upgrades;
        this.config = config;
        this.plugin = plugin;
        setDescription("SkyMinions 管理命令");
        setPermission("minions.admin");
        setUsage("/minion <give <type> [level] | upgrade <module> | materials <type> [level] | skin | reload | purge | list | stats>（图鉴/合成见 /minions）");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        // 显式校验管理权限：不依赖框架对 setPermission 的处理（自定义 Command 子类
        // 未经过 PluginCommand 的 testPermission 路径，必须自行把关）
        if (!sender.hasPermission("minions.admin")) {
            sender.sendMessage(Messages.NO_PERMISSION);
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Messages.USAGE);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> give(sender, args);
            case "upgrade" -> giveUpgrade(sender, args);
            case "materials" -> materials(sender, args);
            case "skin" -> listSkins(sender);
            case "reload" -> reload(sender);
            case "purge" -> purge(sender);
            case "list" -> sender.sendMessage(Messages.totalMinions(manager.all().size()));
            case "stats" -> stats(sender);
            default -> sender.sendMessage(Messages.UNKNOWN_COMMAND);
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.PLAYER_ONLY);
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.USAGE_GIVE);
            return;
        }
        MinionType type = MinionType.fromKey(args[1]).orElse(null);
        if (type == null) {
            sender.sendMessage(Messages.unknownType(args[1]));
            return;
        }
        int level = 1;
        if (args.length >= 3) {
            try {
                level = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                player.sendMessage(Messages.LEVEL_MUST_BE_NUMBER);
                return;
            }
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(items.createItem(type, level));
        dropOverflow(player, overflow);
        player.sendMessage(Messages.givenMinion(type.key(), level));
    }

    private void giveUpgrade(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.PLAYER_ONLY);
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.USAGE_UPGRADE);
            return;
        }
        MinionUpgradeType type = MinionUpgradeType.fromKey(args[1]).orElse(null);
        if (type == null) {
            sender.sendMessage(Messages.unknownUpgrade(args[1]));
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(upgrades.createItem(type));
        dropOverflow(player, overflow);
        player.sendMessage(Messages.givenUpgrade(type.displayName()));
    }

    /** 背包满时把放不下的部分掉落在脚下，绝不静默吞物品。 */
    private static void dropOverflow(Player player, Map<Integer, ItemStack> overflow) {
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    /**
     * /minion materials <类型> [等级]：输出该级升级材料的获取指引
     * （合成配方 / 原版名称 / 其他来源）。控制台与玩家均可执行。
     */
    private void materials(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.USAGE_MATERIALS);
            return;
        }
        MinionType type = MinionType.fromKey(args[1]).orElse(null);
        if (type == null) {
            sender.sendMessage(Messages.unknownType(args[1]));
            return;
        }
        var cfg = config.get().type(type);
        if (cfg == null) {
            sender.sendMessage(Messages.unknownType(args[1]));
            return;
        }
        int level = 1;
        if (args.length >= 3) {
            try {
                level = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                sender.sendMessage(Messages.LEVEL_MUST_BE_NUMBER);
                return;
            }
        }
        if (level >= cfg.maxLevel()) {
            sender.sendMessage(Messages.MAX_LEVEL);
            return;
        }
        sender.sendMessage(Messages.materialsHeader(cfg.displayName(),
                com.hcs.minions.util.Roman.of(level), com.hcs.minions.util.Roman.of(level + 1)));
        if (cfg.overrideLevels().contains(level)) {
            sender.sendMessage(Messages.materialsOverrideNote());
        }
        for (var e : cfg.recipeFor(level).entrySet()) {
            Material mat = e.getKey().guideMaterial();
            sender.sendMessage(Messages.materialsEntry(e.getKey().displayName(), e.getValue()));
            for (var line : com.hcs.minions.util.MaterialGuide.chatLines(mat)) {
                sender.sendMessage(line);
            }
        }
        sender.sendMessage(Messages.materialsBaseNote());
    }

    private void listSkins(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Messages.PLAYER_ONLY);
            return;
        }
        sender.sendMessage(Messages.availableSkins(
                String.join("、", Arrays.stream(MinionSkin.values()).map(MinionSkin::displayName).toList())));
        sender.sendMessage(Messages.SKIN_TIP);
    }

    private void reload(CommandSender sender) {
        config.reload(); // 升级本体开关等配置由消费方直接从配置快照读取，无需静态同步
        Messages.load(plugin);
        GuiText.load(plugin);
        ItemRef.clearCache(); // CraftEngine 自定义物品原型可能在重载后重定义
        sender.sendMessage(Messages.configReloaded());
    }

    private void purge(CommandSender sender) {
        int removed = manager.purgeOrphans();
        sender.sendMessage(Messages.purged(removed));
    }

    /** 运行时统计：运行时长/调度周期/稀有掉落/全场累计产出。 */
    private void stats(CommandSender sender) {
        sender.sendMessage(Messages.statsHeader());
        for (String line : manager.statsLines()) {
            sender.sendMessage(Messages.statsLine(line));
        }
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        // 补全同样受管理权限保护，避免向无权限玩家泄露子命令结构
        if (!sender.hasPermission("minions.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("give", "upgrade", "materials", "skin", "reload", "purge", "list", "stats");
        }
        if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            return MinionType.all().stream().map(MinionType::key).toList();
        }
        if (args.length == 2 && "materials".equalsIgnoreCase(args[0])) {
            return MinionType.all().stream().map(MinionType::key).toList();
        }
        if (args.length == 3 && "materials".equalsIgnoreCase(args[0])) {
            return List.of("1", "8", "10", "12");
        }
        if (args.length == 2 && "upgrade".equalsIgnoreCase(args[0])) {
            return Arrays.stream(MinionUpgradeType.values()).map(MinionUpgradeType::key).toList();
        }
        return List.of();
    }
}
