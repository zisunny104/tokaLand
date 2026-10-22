package dev.toka.land;

import cn.nukkit.Player;
import dev.toka.land.model.ClaimAccess;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.ClaimPermission;
import dev.toka.land.model.LandClaim;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Who may edit an existing player claim, and the edit itself. */
public final class Management {
    private final Main plugin;
    private final LandService lands;
    private final Trading trading;
    private final PlayerNames names;

    Management(Main plugin, LandService lands, Trading trading, PlayerNames names) {
        this.plugin = plugin;
        this.lands = lands;
        this.trading = trading;
        this.names = names;
    }

    public List<LandClaim> claimsAround(Player player) {
        return plugin.findAllAt(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ())
                .stream().filter(claim -> claim.kind() == ClaimKind.PLAYER).toList();
    }

    public UUID resolvePlayer(String query) { plugin.requirePrimaryThread(); return names.resolve(query); }

    public String playerName(UUID id) { plugin.requirePrimaryThread(); return names.find(id).orElse(id.toString()); }

    /** Throws with a stable code when the player may not edit this claim now. */
    public void check(Player player, LandClaim claim) {
        plugin.requirePrimaryThread();
        if (!Main.ownsLand() || plugin.maintenanceLocked()) throw new IllegalStateException("LAND_NOT_ACTIVE");
        if (trading.hasPending(claim.id())) throw new IllegalStateException("TRANSACTION_RECOVERY_PENDING");
        if (claim.kind() != ClaimKind.PLAYER || claim.accessPolicy().locked()) throw new IllegalStateException("CLAIM_LOCKED");
        var providers = plugin.providers();
        if (!player.hasPermission("tokaland.admin") && !ClaimAccess.allows(plugin.index(), claim.id(), player.getUniqueId(),
                ClaimPermission.MANAGE, false, subject -> providers.subjectMatches(player, subject)))
            throw new IllegalStateException("MANAGE_DENIED");
    }

    public CompletableFuture<String> edit(Player player, LandClaim before, LandClaim after) {
        check(player, before);
        return lands.edit(before, after);
    }
}
