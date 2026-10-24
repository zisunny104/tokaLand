package dev.toka.land.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import dev.toka.land.Main;
import dev.toka.land.model.LandClaim;
import java.util.List;
import java.util.UUID;

/** Everyday lookups and administrator tools of /land: info, list by owner, reset and clear. */
final class ClaimTools {
    private final Main plugin;

    ClaimTools(Main plugin) { this.plugin = plugin; }

    private void send(CommandSender sender, String key, Object... args) { sender.sendMessage(plugin.texts().text(sender, key, args)); }

    /** Claims around the player; read-only, so any player with use permission may look. */
    boolean localInfo(Player player) {
        List<LandClaim> here = plugin.management().claimsAround(player);
        if (here.isEmpty()) { send(player, "tools.noneHere"); return true; }
        String language = plugin.texts().language(player);
        for (LandClaim claim : here)
            player.sendMessage(plugin.texts().claimInfo(claim, language));
        return true;
    }

    /** Names of the claims a player owns; the name is resolved to a UUID first, never matched by text. */
    boolean ownerList(CommandSender sender, String query) {
        UUID owner;
        try { owner = plugin.management().resolvePlayer(query); }
        catch (IllegalArgumentException unknown) { send(sender, "tools.unknownPlayer", query); return true; }
        List<LandClaim> owned = plugin.index().ownedBy(owner).stream()
                .sorted(java.util.Comparator.comparing(LandClaim::name, String.CASE_INSENSITIVE_ORDER)).toList();
        send(sender, "tools.ownerHeader", plugin.management().playerName(owner), owned.size());
        owned.forEach(claim -> send(sender, "tools.ownerRow", claim.name()));
        return true;
    }

    boolean reset(Player player, LandClaim target) {
        plugin.management().reset(player, target).whenComplete((revision, error) -> plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            if (error != null) send(player, "purchase.unavailable", reason(error));
            else send(player, "tools.resetDone", target.name());
        }));
        return true;
    }

    /** First call lists what would be deleted; "confirm" deletes exactly that list. */
    boolean clear(Player player, boolean confirm) {
        if (!confirm) {
            List<LandClaim> hits = plugin.management().clearPreview(player);
            if (hits.isEmpty()) { send(player, "tools.clearNone"); return true; }
            send(player, "tools.clearPreview", hits.size());
            hits.forEach(claim -> send(player, "tools.ownerRow", claim.name()));
            return true;
        }
        plugin.management().clearConfirmed(player).whenComplete((count, error) -> plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            if (error != null) send(player, "purchase.unavailable", reason(error));
            else send(player, "tools.clearDone", count);
        }));
        return true;
    }

    private static String reason(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && (root instanceof java.util.concurrent.CompletionException)) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
