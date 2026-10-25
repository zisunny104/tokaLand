package dev.toka.land;

import cn.nukkit.Player;
import dev.toka.land.model.AccessSubject;
import dev.toka.land.model.ClaimAccess;
import dev.toka.land.model.MemberLevel;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.ClaimPermission;
import dev.toka.land.model.LandClaim;
import dev.toka.land.model.AccessPolicy;
import dev.toka.land.model.ClaimDetails;
import dev.toka.land.model.ClaimEdits;
import dev.toka.land.model.ReservedNames;
import dev.toka.land.model.LandSelection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Who may edit an existing player claim, and the edit itself. */
public final class Management implements cn.nukkit.event.Listener {
    private final Main plugin;
    private final LandService lands;
    private final Trading trading;
    private final PlayerNames names;
    private final Map<UUID, List<LandClaim>> clearPreviews = new HashMap<>();

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

    /**
     * A member gives up their own place in a claim. This needs no right to manage, only a grant of the player's own on this claim;
     * the owner and a tenant cannot leave, and a right that comes from a parent claim is given up there.
     */
    public CompletableFuture<String> leave(Player player, LandClaim claim) {
        plugin.requirePrimaryThread();
        if (!Main.ownsLand() || plugin.maintenanceLocked()) throw new IllegalStateException("LAND_NOT_ACTIVE");
        if (trading.hasPending(claim.id())) throw new IllegalStateException("TRANSACTION_RECOVERY_PENDING");
        LandClaim current = plugin.getClaim(claim.id()).orElseThrow(() -> new IllegalStateException("CLAIM_NOT_FOUND"));
        UUID me = player.getUniqueId();
        if (ClaimAccess.effectiveOwner(plugin.index(), current.id()).filter(me::equals).isPresent()) throw new IllegalStateException("LEAVE_OWNER");
        AccessSubject self = AccessSubject.player(me);
        if (MemberLevel.of(current, self) == null)
            throw new IllegalStateException(ClaimAccess.isMember(plugin.index(), current.id(), me) ? "LEAVE_VIA_PARENT" : "NOT_MEMBER");
        return lands.edit(current, ClaimEdits.member(current, self, (MemberLevel) null));
    }

    @cn.nukkit.event.EventHandler public void onQuit(cn.nukkit.event.player.PlayerQuitEvent event) {
        clearPreviews.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Administrators only: creates a claim for the player from the current selection. No payment is taken and the refund
     * value is 0, so a claim handed out this way can never be turned into income. The administrator is recorded as creator.
     */
    public CompletableFuture<String> grant(Player admin, UUID owner, String name) {
        requireAdmin(admin);
        if (ReservedNames.is(name)) throw new IllegalArgumentException("RESERVED_NAME");
        var tool = plugin.selectionTool();
        var draft = tool == null ? null : tool.get(admin.getUniqueId());
        if (draft == null) throw new IllegalStateException("SELECTION_REQUIRED");
        var part = draft.geometry();
        var claim = new LandClaim(UUID.randomUUID(), name, ClaimKind.PLAYER, "land", owner, null, false, false, List.of(part), List.of(),
                AccessPolicy.privateDefault(), Map.of(), null, playerName(owner),
                new ClaimDetails(admin.getUniqueId(), admin.getName(), draft.teleport(), true, 0L, null));
        return lands.create(claim).thenApply(revision -> { tool.forget(admin.getUniqueId()); return name; });
    }

    /** Administrators only: changes what deleting the claim pays back. */
    public CompletableFuture<String> setRefund(Player admin, LandClaim claim, long value) {
        requireAdmin(admin);
        if (value < 0) throw new IllegalArgumentException("INVALID_AMOUNT");
        if (claim.kind() != ClaimKind.PLAYER) throw new IllegalStateException("CLAIM_NOT_PLAYER");
        if (trading.hasPending(claim.id())) throw new IllegalStateException("TRANSACTION_RECOVERY_PENDING");
        return lands.edit(claim, ClaimEdits.refund(claim, value));
    }

    private LandSelection draft(Player player) {
        var tool = plugin.selectionTool();
        var draft = tool == null ? null : tool.get(player.getUniqueId());
        if (draft == null) throw new IllegalStateException("SELECTION_REQUIRED");
        return draft.geometry();
    }

    private void requireAdmin(Player player) {
        plugin.requirePrimaryThread();
        if (!Main.ownsLand() || plugin.maintenanceLocked()) throw new IllegalStateException("LAND_NOT_ACTIVE");
        if (!player.hasPermission("tokaland.admin")) throw new IllegalStateException("MANAGE_DENIED");
    }

    /** Administrators only: gives the claim the current selection as its whole shape. */
    public CompletableFuture<String> reset(Player player, LandClaim claim) {
        requireAdmin(player);
        if (trading.hasPending(claim.id())) throw new IllegalStateException("TRANSACTION_RECOVERY_PENDING");
        if (claim.kind() != ClaimKind.PLAYER) throw new IllegalStateException("CLAIM_NOT_PLAYER");
        return lands.edit(claim, ClaimEdits.reshape(claim, draft(player)));
    }

    /** Player claims the selection touches. Remembered per administrator so {@link #clearConfirmed} deletes exactly this list. */
    public List<LandClaim> clearPreview(Player player) {
        requireAdmin(player);
        var part = draft(player);
        List<LandClaim> hits = plugin.allClaims().stream().filter(c -> c.kind() == ClaimKind.PLAYER)
                .filter(c -> c.selections().stream().anyMatch(s -> LandService.overlaps(s, part)))
                .sorted(java.util.Comparator.comparing(c -> c.id().toString())).toList();
        if (hits.isEmpty()) clearPreviews.remove(player.getUniqueId());
        else clearPreviews.put(player.getUniqueId(), hits);
        return hits;
    }

    /** Deletes the previewed claims in one commit, without refunds. Fails if any of them changed since the preview. */
    public CompletableFuture<Integer> clearConfirmed(Player player) {
        requireAdmin(player);
        List<LandClaim> previewed = clearPreviews.remove(player.getUniqueId());
        if (previewed == null) throw new IllegalStateException("CLEAR_PREVIEW_REQUIRED");
        for (LandClaim claim : previewed) if (trading.hasPending(claim.id())) throw new IllegalStateException("TRANSACTION_RECOVERY_PENDING");
        return lands.deleteAll(previewed).thenApply(revision -> previewed.size());
    }
}
