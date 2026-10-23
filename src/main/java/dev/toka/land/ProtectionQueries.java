package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.toka.land.model.*;
import dev.toka.land.protection.ExplosionAttribution;
import dev.toka.land.protection.ExplosionRules;
import dev.toka.land.protection.PvpPolicy;
import java.util.*;
import java.util.function.Predicate;

/** Answers "may this player do this here" and "may this blast go off"; every claim hit takes part in the answer. */
public final class ProtectionQueries {
    private final Main plugin;

    ProtectionQueries(Main plugin) { this.plugin = plugin; }

    static String notActive() { return reply("UNAVAILABLE", false, "LAND_NOT_ACTIVE", List.of(), List.of()); }

    /** Main-thread JSON reply. Anything but ALLOW must be treated as a denial. */
    public String query(Player player, String world, int x, int y, int z, String action) {
        if (!Main.ownsLand()) return notActive();
        if (plugin.maintenanceLocked()) return reply("DENY", false, "MAINTENANCE_LOCK", List.of(), List.of());
        if (player == null || !player.isOnline() || world == null || world.isBlank() || action == null)
            return reply("INVALID_REQUEST", false, "INVALID_ACTOR_OR_TARGET", List.of(), List.of());
        final ClaimPermission permission;
        try { permission = ClaimPermission.valueOf(action.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return reply("INVALID_REQUEST", false, "UNKNOWN_ACTION", List.of(), List.of()); }
        var index = plugin.index();
        var hits = index.findAllAt(world, x, y, z);
        var providers = plugin.providers();
        // LOCK bypass stays closed until durable audit exists, so the bypass flag is always false here.
        var actor = new ProtectionDecision.Actor(player.getUniqueId(), player.hasPermission("tokaland.admin"),
                player.hasPermission("tokaland.lock-bypass"), false);
        var result = ProtectionDecision.evaluate(index, hits, actor, permission, false,
                (claim, requested) -> providers.externalAuthority(player, claim, requested), subject -> providers.subjectMatches(player, subject));
        String reason = result.reason();
        if (!result.allowed() && hits.stream().anyMatch(c -> result.blockingClaims().contains(c.id()) && c.accessPolicy().locked()))
            reason = "LOCK_BYPASS_AUDIT_UNAVAILABLE";
        return reply(result.allowed() ? "ALLOW" : "DENY", result.allowed(), reason,
                hits.stream().map(LandClaim::id).toList(), result.blockingClaims());
    }

    /** A blast with no known range: cancelled whenever the server policy is to protect first. */
    public boolean cancelUnknownBlastRange() {
        var rules = plugin.explosionRules();
        return rules != null && rules.mode() == ExplosionRules.Mode.CANCEL_WHOLE;
    }

    /** The reach is twice the force, as in the core's damage radius; any claim in it that the cause may not touch cancels the blast. */
    public boolean cancelEntityExplosion(Entity source, Position center, double force) {
        var rules = plugin.explosionRules();
        if (rules == null || center == null || center.getLevel() == null || !Double.isFinite(force) || force < 0) return true;
        double radius = force * 2d;
        var bounds = new ExplosionRules.Bounds(center.getLevel().getFolderName(),
                Math.floor(center.x - radius - 1d), Math.floor(center.y - radius - 1d), Math.floor(center.z - radius - 1d),
                Math.ceil(center.x + radius + 1d), Math.ceil(center.y + radius + 1d), Math.ceil(center.z + radius + 1d));
        var index = plugin.index();
        var providers = plugin.providers();
        var attribution = ExplosionAttribution.resolve(source, rules.attributionMaxDepth());
        Player cause = attribution.playerId() == null ? null : plugin.getServer().getOnlinePlayers().values().stream()
                .filter(candidate -> candidate.getUniqueId().equals(attribution.playerId())).findFirst().orElse(null);
        Predicate<LandClaim> permitted = claim -> {
            if (plugin.maintenanceLocked() || claim.accessPolicy().locked()) return false;
            if (cause != null && cause.hasPermission("tokaland.admin")) return true;
            if (claim.accessPolicy().mode() == AccessPolicy.Mode.OPEN) return true;
            return cause != null && (ClaimAccess.allows(index, claim.id(), attribution.playerId(), ClaimPermission.BUILD, false,
                    subject -> providers.subjectMatches(cause, subject))
                    || claim.accessPolicy().mode() == AccessPolicy.Mode.PROTECT && providers.externalAuthority(cause, claim, ClaimPermission.BUILD));
        };
        var decision = rules.evaluate(index, bounds, attribution, permitted);
        boolean cancel = plugin.maintenanceLocked() && !decision.affectedClaims().isEmpty() || decision.cancel();
        if (cancel) plugin.texts().info("log.blastCancelled", decision.reason(), decision.affectedClaims().size());
        return cancel;
    }

    /** Natural changes inside claims, or anywhere while the maintenance lock is on, are protected. */
    public boolean protectedEnvironment(Position target) {
        if (!Main.ownsLand() || target == null || target.getLevel() == null) return false;
        return plugin.maintenanceLocked() || !plugin.index().findAllAt(target.getLevel().getFolderName(),
                target.getFloorX(), target.getFloorY(), target.getFloorZ()).isEmpty();
    }

    /** True when a spreading change would move between different sets of claims, in or out. */
    public boolean crossesBoundary(Block from, Block to, String configKey) {
        if (!Main.ownsLand() || !plugin.getConfig().getBoolean(configKey, true) || from == null || to == null
                || from.getLevel() == null || to.getLevel() == null) return false;
        var index = plugin.index();
        var source = ids(index.findAllAt(from.getLevel().getFolderName(), from.getFloorX(), from.getFloorY(), from.getFloorZ()));
        var destination = ids(index.findAllAt(to.getLevel().getFolderName(), to.getFloorX(), to.getFloorY(), to.getFloorZ()));
        return (!source.isEmpty() || !destination.isEmpty()) && !source.equals(destination);
    }

    private static Set<UUID> ids(List<LandClaim> claims) {
        Set<UUID> ids = new HashSet<>();
        claims.forEach(claim -> ids.add(claim.id()));
        return ids;
    }

    /** Both players in PvP claims: the listener then overrides damage protection that something else applied. */
    public boolean pvpAllowed(Player victim, Player attacker) {
        if (!Main.ownsLand() || plugin.maintenanceLocked()) return false;
        return PvpPolicy.allows(plugin.findAllAt(victim.getLevel().getFolderName(), victim.getFloorX(), victim.getFloorY(), victim.getFloorZ()),
                plugin.findAllAt(attacker.getLevel().getFolderName(), attacker.getFloorX(), attacker.getFloorY(), attacker.getFloorZ()));
    }

    private static String reply(String status, boolean allowed, String reason, List<UUID> hits, List<UUID> blocked) {
        JsonObject reply = new JsonObject();
        reply.addProperty("status", status);
        reply.addProperty("allowed", allowed);
        reply.addProperty("reason", reason);
        JsonArray all = new JsonArray();
        hits.forEach(id -> all.add(id.toString()));
        reply.add("claims", all);
        JsonArray denied = new JsonArray();
        blocked.forEach(id -> denied.add(id.toString()));
        reply.add("blockingClaims", denied);
        return reply.toString();
    }
}
