package dev.toka.land.lease;

import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The leases, one document each under {@code lease.<claim id>}, all kept in memory as well. */
public final class LeaseBook {
    private static final String PREFIX = "lease.";
    private final DocumentStore store;
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();

    public LeaseBook(DocumentStore store) throws IOException {
        this.store = store;
        for (String key : store.keys()) {
            if (!key.startsWith(PREFIX)) continue;
            var json = store.load(key).orElse(null);
            if (json != null) leases.put(key.substring(PREFIX.length()), Lease.fromJson(json));
        }
    }

    public Lease get(UUID claimId) { return leases.get(claimId.toString()); }

    public Collection<Lease> all() { return leases.values(); }

    /** Writes the lease and reads it back before it counts */
    public synchronized void save(Lease lease) throws IOException {
        var json = lease.toJson();
        store.save(PREFIX + lease.claimId, json);
        if (!store.load(PREFIX + lease.claimId).orElseThrow().equals(json)) throw new IOException("Lease readback mismatch");
        leases.put(lease.claimId, lease);
    }

    public synchronized void delete(UUID claimId) throws IOException {
        store.delete(PREFIX + claimId);
        leases.remove(claimId.toString());
    }
}
