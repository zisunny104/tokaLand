package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.utils.Config;
import dev.toka.land.model.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Roads, rails and other facility claims, created by administrators from the selection tool. */
public final class Facilities {
    private final Main plugin;
    private final LandService lands;
    private final FacilityTypes types;

    Facilities(Main plugin, LandService lands, Config config) {
        this.plugin = plugin;
        this.lands = lands;
        Map<String, FacilityTypes.Reach> reach = new HashMap<>();
        var section = config.getSection("facility.types");
        for (String name : section.getKeys(false))
            reach.put(name, new FacilityTypes.Reach(section.getInt(name + ".below", 0), section.getInt(name + ".above", 0)));
        this.types = new FacilityTypes(reach);
    }

    public FacilityTypes types() { return types; }

    /** Completes with CREATED, or EXTENDED when a facility of the same name and type gained a part. */
    public CompletableFuture<String> save(Player player, String type, String name) {
        plugin.requirePrimaryThread();
        if (!Main.ownsLand() || plugin.maintenanceLocked()) throw new IllegalStateException("LAND_NOT_ACTIVE");
        if (!player.hasPermission("tokaland.admin")) throw new IllegalStateException("MANAGE_DENIED");
        if (!types.has(type)) throw new IllegalArgumentException("UNKNOWN_FACILITY_TYPE");
        var tool = plugin.selectionTool();
        var draft = tool == null ? null : tool.get(player.getUniqueId());
        if (draft == null || draft.first() == null || draft.second() == null || !draft.first().world().equals(draft.second().world()))
            throw new IllegalStateException("SELECTION_REQUIRED");
        String kind = type.toLowerCase(Locale.ROOT);
        var part = types.select(kind, draft.first().world(), draft.first().x(), draft.first().y(), draft.first().z(),
                draft.second().x(), draft.second().y(), draft.second().z());
        var existing = name == null ? null : plugin.allClaims().stream()
                .filter(c -> c.kind() == ClaimKind.FACILITY && c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        CompletableFuture<String> work;
        String status;
        if (existing != null) {
            if (!existing.type().equals(kind)) throw new IllegalStateException("FACILITY_TYPE_MISMATCH");
            if (existing.accessPolicy().locked()) throw new IllegalStateException("CLAIM_LOCKED");
            work = lands.edit(existing, ClaimEdits.addSelection(existing, part));
            status = "EXTENDED";
        } else {
            UUID id = UUID.randomUUID();
            String label = name == null || name.isBlank() ? kind + "-" + id.toString().substring(0, 8) : name.strip();
            if (ReservedNames.is(label)) throw new IllegalArgumentException("RESERVED_NAME");
            // PROTECT: only administrators and the registered external authority may build here.
            var claim = new LandClaim(id, label, ClaimKind.FACILITY, kind, null, null, false, false, List.of(part), List.of(),
                    new AccessPolicy(AccessPolicy.Mode.PROTECT, false), Map.of(), null, null,
                    new ClaimDetails(player.getUniqueId(), player.getName(), null, false));
            work = lands.create(claim);
            status = "CREATED";
        }
        return work.thenApply(revision -> { tool.forget(player.getUniqueId()); return status; });
    }
}
