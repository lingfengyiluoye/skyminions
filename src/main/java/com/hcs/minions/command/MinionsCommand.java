package com.hcs.minions.command;

import com.hcs.minions.gui.CollectionGui;
import com.hcs.minions.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家命令：/minions 打开仆从图鉴 GUI（对齐文档的核心入口）。
 * 与管理员命令 /minion 分离：本命令无权限要求，所有玩家可用。
 */
public final class MinionsCommand extends Command {

    private final CollectionGui gui;

    public MinionsCommand(CollectionGui gui) {
        super("minions");
        this.gui = gui;
        setDescription("打开仆从图鉴");
        setUsage("/minions");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.PLAYER_ONLY);
            return true;
        }
        gui.open(player, null, 0);
        return true;
    }
}
