package dev.toka.land;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.toka.land.model.LandClaim;
import dev.toka.land.storage.DatasetRepository;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Whole-dataset export and replacement for offline administration and the conversion tool; no legacy formats. */
final class DatasetAPI {
    private static final Set<String> FIELDS = Set.of("format", "claims", "revision");
    private final LandService lands;
    private final Trading trading;

    DatasetAPI(LandService lands, Trading trading) {
        this.lands = lands;
        this.trading = trading;
    }

    String export() {
        var snapshot = lands.snapshot();
        var out = new JsonObject();
        out.addProperty("format", 1);
        out.addProperty("revision", snapshot.revision());
        var rows = new JsonArray();
        snapshot.index().all().stream().sorted(Comparator.comparing(c -> c.id().toString())).forEach(c -> rows.add(DatasetRepository.encode(c)));
        out.add("claims", rows);
        return out.toString();
    }

    /** For tokaWorlds after a world was renamed; refused while a transaction is unfinished, since it still names the old world. */
    CompletableFuture<Integer> renameWorld(String from, String to) {
        if (trading.hasPending()) throw new IllegalStateException("Unfinished land transactions must be recovered before renaming a world");
        return lands.renameWorld(from, to);
    }

    /** Refused while Land owns protection or any transaction is unfinished. */
    CompletableFuture<String> replace(String expectedRevision, String json) {
        if (Main.ownsLand()) throw new IllegalStateException("Bulk replacement requires inactive ownership");
        if (trading.hasPending()) throw new IllegalStateException("Unfinished land transactions must be recovered before dataset replacement");
        var envelope = JsonParser.parseString(json).getAsJsonObject();
        DatasetRepository.requireSupportedFormat(envelope.get("format"));
        for (String field : envelope.keySet())
            if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported dataset field: " + field);
        List<LandClaim> claims = new ArrayList<>();
        for (var row : envelope.getAsJsonArray("claims")) claims.add(DatasetRepository.decode(row.getAsJsonObject()));
        return lands.replace(expectedRevision, claims).thenApply(revision -> export());
    }
}
