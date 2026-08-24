package com.hcs.minions.command;

import com.hcs.minions.config.ConfigProvider;
import com.hcs.minions.model.Minion;
import com.hcs.minions.model.MinionSkin;
import com.hcs.minions.model.MinionType;
import com.hcs.minions.service.CollectionService;
import com.hcs.minions.service.MinionItemService;
import com.hcs.minions.service.MinionManager;
import com.hcs.minions.upgrade.MinionUpgradeType;
import com.hcs.minions.upgrade.UpgradeService;
import com.hcs.minions.util.GuiText;
import com.hcs.minions.util.ItemRef;
import com.hcs.minions.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

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
    private final CollectionService collection;
    private final JavaPlugin plugin;

    public MinionCommand(MinionItemService items, MinionManager manager, UpgradeService upgrades, ConfigProvider config,
                         CollectionService collection, JavaPlugin plugin) {
        super("minion");
        this.items = items;
        this.manager = manager;
        this.upgrades = upgrades;
        this.config = config;
        this.collection = collection;
        this.plugin = plugin;
        setDescription("SkyMinions 管理命令");
        setPermission("hcs.minions.admin");
        setUsage("/minion <give <type> [level] | upgrade <module> | skin | collection | reload | purge | list>");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Messages.USAGE);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> give(sender, args);
            case "upgrade" -> giveUpgrade(sender, args);
            case "skin" -> listSkins(sender);
            case "reload" -> reload(sender);
            case "purge" -> purge(sender);
            case "collection" -> showCollection(sender);
            case "list" -> sender.sendMessage(Messages.totalMinions(manager.all().size()));
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
        player.getInventory().addItem(items.createItem(type, level));
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
        player.getInventory().addItem(upgrades.createItem(type));
        player.sendMessage(Messages.givenUpgrade(type.displayName()));
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
        Minion.requirePreviousBody = config.reload().upgradeRequirePreviousBody(); // 重载后同步升级本体开关
        Messages.load(plugin);
        GuiText.load(plugin);
        ItemRef.clearCache(); // CraftEngine 自定义物品原型可能在重载后重定义
        sender.sendMessage(Messages.configReloaded());
    }

    private void purge(CommandSender sender) {
        int removed = manager.purgeOrphans();
        sender.sendMessage(Messages.purged(removed));
    }

    private void showCollection(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.PLAYER_ONLY);
            return;
        }
        java.util.Map<String, long[]> progress = collection.progressOf(player.getUniqueId());
        if (progress.isEmpty()) {
            player.sendMessage(Messages.COLLECTION_EMPTY);
            return;
        }
        player.sendMessage(Messages.COLLECTION_HEADER);
        progress.forEach((material, p) -> {
            player.sendMessage(Messages.collectionEntry(material, p[0]));
            if (p[1] > 0) {
                player.sendMessage(Messages.collectionNext(p[0], p[1]));
            } else {
                player.sendMessage(Messages.COLLECTION_COMPLETE);
            }
        });
        int bonus = collection.bonusSlots(player.getUniqueId());
        if (bonus > 0) {
            player.sendMessage(Messages.collectionSlotBonus(bonus));
        }
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("give", "upgrade", "skin", "collection", "reload", "purge", "list");
        }
        if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            return Arrays.stream(MinionType.values()).map(MinionType::key).toList();
        }
        if (args.length == 2 && "upgrade".equalsIgnoreCase(args[0])) {
            return Arrays.stream(MinionUpgradeType.values()).map(MinionUpgradeType::key).toList();
        }
        return List.of();
    }
}
