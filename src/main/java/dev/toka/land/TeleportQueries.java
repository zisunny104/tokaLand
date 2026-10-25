package dev.toka.land;

import cn.nukkit.Player;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.toka.land.model.*;
import java.util.*;

/** Teleport lookups for tokaPortal and tokaSigns; answers are JSON so no Land class crosses a plugin loader. */
final class TeleportQueries {
    private TeleportQueries() {}

    private static boolean hasTarget(LandClaim claim) {
        return claim.kind() == ClaimKind.PLAYER && claim.details().teleport() != null;
    }

    static String targets(LandIndex index, Player player) {
        if (player == null || !player.isOnline()) return reply("INVALID_REQUEST", new JsonArray());
        JsonArray rows = new JsonArray();
        index.ownedBy(player.getUniqueId()).stream().filter(TeleportQueries::hasTarget)
                .sorted(Comparator.comparing(LandClaim::name, String.CASE_INSENSITIVE_ORDER).thenComparing(claim -> claim.id().toString()))
                .forEach(claim -> rows.add(target(claim)));
        JsonObject reply = new JsonObject();
        reply.addProperty("status", "READY");
        reply.add("targets", rows);
        return reply.toString();
    }

    static String target(LandIndex index, Player player, String idOrName) {
        if (player == null || !player.isOnline() || idOrName == null || idOrName.isBlank())
            return reply("INVALID_REQUEST", new JsonArray());
        boolean admin = player.hasPermission("tokaland.admin");
        List<LandClaim> usable = index.all().stream().filter(TeleportQueries::hasTarget)
                .filter(claim -> TeleportAccess.permits(index, claim, player.getUniqueId(), admin))
                .toList();
        Lookup found = find(index, usable, idOrName.strip());
        if (found.claim == null) {
            // A known claim the player may not use is reported as denied, never as missing.
            boolean exists = found.status.equals("NOT_FOUND") && uuid(idOrName.strip()).flatMap(index::get).filter(TeleportQueries::hasTarget).isPresent();
            return reply(exists ? "DENIED" : found.status, new JsonArray());
        }
        JsonArray rows = new JsonArray();
        rows.add(target(found.claim));
        return reply("OK", rows);
    }

    static String display(LandIndex index, String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return reply("INVALID_REQUEST", new JsonArray());
        Lookup found = find(index, index.all().stream().filter(TeleportQueries::hasTarget).toList(), idOrName.strip());
        if (found.claim == null) return reply(found.status, new JsonArray());
        JsonObject row = new JsonObject();
        row.addProperty("id", found.claim.id().toString());
        row.addProperty("name", found.claim.name());
        JsonArray rows = new JsonArray();
        rows.add(row);
        return reply("OK", rows);
    }

    static String reply(String status, JsonArray targets) {
        JsonObject reply = new JsonObject();
        reply.addProperty("status", status);
        reply.add("targets", targets);
        return reply.toString();
    }

    private record Lookup(LandClaim claim, String status) {}

    /** A UUID wins over a name; a name must match exactly one candidate. */
    private static Lookup find(LandIndex index, List<LandClaim> candidates, String request) {
        Optional<UUID> id = uuid(request);
        if (id.isPresent()) {
            var byId = index.get(id.get());
            if (byId.isPresent() && candidates.contains(byId.get())) return new Lookup(byId.get(), "OK");
        }
        List<LandClaim> matches = candidates.stream().filter(claim -> claim.name().equalsIgnoreCase(request)).toList();
        if (matches.size() > 1) return new Lookup(null, "AMBIGUOUS");
        return matches.isEmpty() ? new Lookup(null, "NOT_FOUND") : new Lookup(matches.getFirst(), "OK");
    }

    private static Optional<UUID> uuid(String text) {
        try { return Optional.of(UUID.fromString(text)); } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private static JsonObject target(LandClaim claim) {
        var point = claim.details().teleport();
        JsonObject row = new JsonObject();
        row.addProperty("id", claim.id().toString());
        row.addProperty("name", claim.name());
        row.addProperty("world", point.world());
        row.addProperty("x", point.x());
        row.addProperty("y", point.y());
        row.addProperty("z", point.z());
        row.addProperty("yaw", point.yaw());
        row.addProperty("pitch", point.pitch());
        return row;
    }
}
