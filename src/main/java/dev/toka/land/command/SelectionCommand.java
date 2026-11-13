package dev.toka.land.command;

import cn.nukkit.Player;
import cn.nukkit.command.data.*;
import dev.toka.land.Main;
import dev.toka.land.protection.SelectionToolListener.Point;

final class SelectionCommand {
    private SelectionCommand() { }

    static CommandParameter[] parameters(String prefix) {
        return new CommandParameter[]{
                CommandParameter.newEnum("action", new CommandEnum(prefix + "Select", new String[]{"select"})),
                CommandParameter.newEnum("type", new CommandEnum(prefix + "SelectionType", new String[]{"2d", "3d"})),
                CommandParameter.newType("A", CommandParamType.BLOCK_POSITION),
                CommandParameter.newType("B", CommandParamType.BLOCK_POSITION)};
    }

    static boolean execute(Main plugin, Player player, String[] args, String usage) {
        var tool = plugin.selectionTool();
        if (!Main.ownsLand() || tool == null) {
            player.sendMessage(plugin.texts().text(player, "purchase.unavailable", "SELECTION_UNAVAILABLE"));
            return true;
        }
        if (args.length != 8 || !(args[1].equalsIgnoreCase("2d") || args[1].equalsIgnoreCase("3d"))) {
            player.sendMessage(plugin.texts().text(player, usage));
            return true;
        }
        try {
            var level = player.getLevel();
            Point first = point(level.getFolderName(), args, 2);
            Point second = point(level.getFolderName(), args, 5);
            boolean flat = args[1].equalsIgnoreCase("2d");
            if (!flat && (Math.min(first.y(), second.y()) < level.getMinBlockY()
                    || Math.max(first.y(), second.y()) > level.getMaxBlockY())) {
                player.sendMessage(plugin.texts().text(player, "select.outsideHeight", level.getMinBlockY(), level.getMaxBlockY()));
                return true;
            }
            tool.select(player, first, second, flat);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            player.sendMessage(plugin.texts().text(player, usage));
        }
        return true;
    }

    private static Point point(String world, String[] args, int index) {
        return new Point(world, Integer.parseInt(args[index]), Integer.parseInt(args[index + 1]), Integer.parseInt(args[index + 2]));
    }
}
