package dev.toka.land.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.toka.land.model.LandClaim;
import dev.toka.storage.DocumentStore;

import java.io.IOException;
import java.util.*;

/** Stores one document per claim so ordinary edits do not rewrite the whole cadastral database. */
public final class LandRepository {
    private static final String PREFIX = "claim.";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final DocumentStore store;

    public LandRepository(DocumentStore store) { this.store = Objects.requireNonNull(store); }

    public List<LandClaim> loadAll() throws IOException {
        List<LandClaim> result = new ArrayList<>();
        for (String key : new TreeSet<>(store.keys())) {
            if (!key.startsWith(PREFIX)) continue;
            JsonObject document = store.load(key).orElseThrow(() -> new IOException("Claim document disappeared: " + key));
            LandClaim claim = DatasetRepository.decode(document.getAsJsonObject("claim"));
            if (claim == null || !key.equals(key(claim.id()))) throw new IOException("Claim key/payload mismatch: " + key);
            result.add(claim);
        }
        return List.copyOf(result);
    }

    public void save(LandClaim claim) throws IOException {
        JsonObject document = new JsonObject();
        document.addProperty("kind", "land-claim");
        document.add("claim", GSON.toJsonTree(claim));
        store.save(key(claim.id()), document);
    }

    public void delete(UUID id) throws IOException { store.delete(key(id)); }

    private static String key(UUID id) { return PREFIX + id; }
}
