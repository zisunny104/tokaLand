package dev.toka.land.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.*;
import dev.toka.land.Main;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.ClaimTargets;
import dev.toka.land.model.LandSelection;
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

    static CommandParameter[] expandParameters(String prefix) {
        return expandParameters(prefix, true);
    }

    static CommandParameter[] expandParameters(String prefix, boolean explicitType) {
        var parameters = new java.util.ArrayList<CommandParameter>();
        parameters.add(CommandParameter.newEnum("action", new CommandEnum(prefix + "Expand", new String[]{"expand"})));
        if (explicitType) parameters.add(CommandParameter.newEnum("type",
                new CommandEnum(prefix + "ExpandSelectionType", new String[]{"2d", "3d"})));
        parameters.add(CommandParameter.newType("A", CommandParamType.BLOCK_POSITION));
        parameters.add(CommandParameter.newType("B", CommandParamType.BLOCK_POSITION));
        parameters.add(CommandParameter.newType("claim", true, CommandParamType.STRING));
        parameters.add(CommandParameter.newType("world", true, CommandParamType.STRING));
        return parameters.toArray(CommandParameter[]::new);
    }

    static CommandParameter[] defaultSelectionParameters(String prefix) {
        return new CommandParameter[]{
                CommandParameter.newEnum("action", new CommandEnum(prefix + "DefaultSelect", new String[]{"select"})),
                CommandParameter.newType("A", CommandParamType.BLOCK_POSITION),
                CommandParameter.newType("B", CommandParamType.BLOCK_POSITION)};
    }

    static boolean expand(Main plugin, CommandSender sender, String[] args, ClaimKind kind) {
        if (!sender.hasPermission("tokaland.admin")) {
            send(plugin, sender, "command.denied");
            return true;
        }
        boolean facility = kind == ClaimKind.FACILITY;
        Player player = sender instanceof Player p ? p : null;
        boolean explicit = args.length > 1 && isType(args[1]);
        int corner = explicit ? 2 : 1;
        int target = corner + 6;
        if (args.length < target + (player == null ? 1 : 0) || args.length > target + 2
                || player == null && !explicit) {
            send(plugin, sender, facility ? "help.facExpand" : "help.expand");
            return true;
        }
        try {
            var candidates = args.length > target ? plugin.allClaims()
                    : plugin.findAllAt(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ());
            var claim = ClaimTargets.resolve(candidates.stream().filter(c -> c.kind() == kind).toList(),
                    args.length > target ? args[target] : null);
            String world = args.length == target + 2 ? args[target + 1] : claim.world();
            if (!claim.world().equals(world)) {
                send(plugin, sender, "select.wrongWorld", claim.world());
                return true;
            }
            var level = plugin.getServer().getLevelByName(world);
            if (level == null) {
                send(plugin, sender, "select.worldUnavailable", world);
                return true;
            }
            Point first = point(level.getFolderName(), args, corner);
            Point second = point(level.getFolderName(), args, corner + 3);
            boolean flat = selectionMode(plugin, player, args, explicit, first, second);
            if (!flat && (Math.min(first.y(), second.y()) < level.getMinBlockY()
                    || Math.max(first.y(), second.y()) > level.getMaxBlockY())) {
                send(plugin, sender, "select.outsideHeight", level.getMinBlockY(), level.getMaxBlockY());
                return true;
            }
            var part = new LandSelection(first.world(), first.x(), first.y(), first.z(),
                    second.x(), second.y(), second.z(), flat);
            var work = facility ? plugin.facilities().expand(sender, claim.id().toString(), part)
                    : plugin.management().expand(sender, claim, part);
            work.whenComplete((revision, error) -> plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                if (error == null) send(plugin, sender, "select.expanded", claim.name(), part.id());
                else {
                    Throwable cause = error;
                    while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                    send(plugin, sender, "purchase.unavailable", cause.getMessage());
                }
            }));
        } catch (ClaimTargets.Ambiguous ambiguous) {
            send(plugin, sender, "management.ambiguous");
            for (var claim : ambiguous.matches()) send(plugin, sender, "management.target", claim.name(), claim.id());
        } catch (NumberFormatException invalid) {
            send(plugin, sender, facility ? "help.facExpand" : "help.expand");
        } catch (IllegalArgumentException | IllegalStateException failure) {
            send(plugin, sender, "purchase.unavailable", failure.getMessage());
        }
        return true;
    }

    private static void send(Main plugin, CommandSender sender, String key, Object... args) {
        sender.sendMessage(plugin.texts().text(sender, key, args));
    }
    private static boolean ignoreHeight(String type) {
        if (type.equalsIgnoreCase("2d")) return true;
        if (type.equalsIgnoreCase("3d")) return false;
        throw new IllegalArgumentException("INVALID_SELECTION_TYPE");
    }

    private static boolean isType(String type) {
        return type.equalsIgnoreCase("2d") || type.equalsIgnoreCase("3d");
    }

    private static boolean selectionMode(Main plugin, Player player, String[] args, boolean explicit,
                                         Point first, Point second) {
        if (explicit) return ignoreHeight(args[1]);
        boolean ignoreHeight = plugin.trading().ignoreHeight(player, first);
        if (ignoreHeight != plugin.trading().ignoreHeight(player, second))
            throw new IllegalArgumentException("SELECTION_HEIGHT_MODE_MISMATCH");
        return ignoreHeight;
    }

    static boolean execute(Main plugin, Player player, String[] args, String usage) {
        var tool = plugin.selectionTool();
        if (!Main.ownsLand() || tool == null) {
            player.sendMessage(plugin.texts().text(player, "purchase.unavailable", "SELECTION_UNAVAILABLE"));
            return true;
        }
        boolean explicit = args.length == 8 && isType(args[1]);
        if (args.length != 7 && !explicit) {
            player.sendMessage(plugin.texts().text(player, usage));
            return true;
        }
        try {
            var level = player.getLevel();
            int corner = explicit ? 2 : 1;
            Point first = point(level.getFolderName(), args, corner);
            Point second = point(level.getFolderName(), args, corner + 3);
            if (explicit) {
                plugin.trading().ignoreHeight(player, first);
                plugin.trading().ignoreHeight(player, second);
            }
            boolean flat = selectionMode(plugin, player, args, explicit, first, second);
            if (!flat && (Math.min(first.y(), second.y()) < level.getMinBlockY()
                    || Math.max(first.y(), second.y()) > level.getMaxBlockY())) {
                player.sendMessage(plugin.texts().text(player, "select.outsideHeight", level.getMinBlockY(), level.getMaxBlockY()));
                return true;
            }
            tool.select(player, first, second, flat);
        } catch (NumberFormatException invalid) {
            player.sendMessage(plugin.texts().text(player, usage));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            send(plugin, player, "purchase.unavailable", failure.getMessage());
        }
        return true;
    }

    private static Point point(String world, String[] args, int index) {
        return new Point(world, Integer.parseInt(args[index]), Integer.parseInt(args[index + 1]), Integer.parseInt(args[index + 2]));
    }
}
