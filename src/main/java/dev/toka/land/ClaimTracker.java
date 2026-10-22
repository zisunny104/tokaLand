package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerTeleportEvent;
import cn.nukkit.level.Position;
import dev.toka.land.event.PlayerEnterClaimEvent;
import dev.toka.land.event.PlayerLeaveClaimEvent;
import dev.toka.land.model.LandClaim;
import java.util.*;

/** Remembers which claims each player is inside and announces changes as enter and leave events. */
final class ClaimTracker implements Listener {
    private final Main plugin;
    private final Map<UUID, Map<UUID, LandClaim>> inside = new HashMap<>();

    ClaimTracker(Main plugin) { this.plugin = plugin; }

    @EventHandler public void onJoin(PlayerJoinEvent event) { update(event.getPlayer(), event.getPlayer().getPosition()); }

    @EventHandler(ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent event) { update(event.getPlayer(), event.getTo()); }

    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent event) {
        Position from = event.getFrom(), to = event.getTo();
        if (from.getLevel() != to.getLevel() || from.getFloorX() != to.getFloorX()
                || from.getFloorY() != to.getFloorY() || from.getFloorZ() != to.getFloorZ())
            update(event.getPlayer(), to);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        var before = inside.remove(player.getUniqueId());
        if (before != null) before.values().forEach(claim -> fireLeave(player, claim, player.getPosition()));
    }

    private void update(Player player, Position at) {
        UUID id = player.getUniqueId();
        if (!Main.ownsLand() || at.getLevel() == null) {
            var before = inside.remove(id);
            if (before != null) before.values().forEach(claim -> fireLeave(player, claim, at));
            return;
        }
        Map<UUID, LandClaim> now = new HashMap<>();
        plugin.findAllAt(at.getLevel().getFolderName(), at.getFloorX(), at.getFloorY(), at.getFloorZ()).forEach(claim -> now.put(claim.id(), claim));
        Map<UUID, LandClaim> before = inside.getOrDefault(id, Map.of());
        if (now.keySet().equals(before.keySet())) return;
        inside.put(id, now);
        before.forEach((claimId, claim) -> { if (!now.containsKey(claimId)) fireLeave(player, claim, at); });
        now.forEach((claimId, claim) -> { if (!before.containsKey(claimId)) fireEnter(player, claim, at); });
    }

    private void fireEnter(Player player, LandClaim claim, Position at) {
        plugin.getServer().getPluginManager().callEvent(new PlayerEnterClaimEvent(player, claim.id(), claim.name(), claim.kind().name(), claim.type(), at));
    }

    private void fireLeave(Player player, LandClaim claim, Position at) {
        plugin.getServer().getPluginManager().callEvent(new PlayerLeaveClaimEvent(player, claim.id(), claim.name(), claim.kind().name(), claim.type(), at));
    }
}
