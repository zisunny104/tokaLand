package dev.toka.land.lease;

import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The auctions, one document each under {@code auction.<id>}, all kept in memory as well. A change is made on a copy and counts once it is read back. */
public final class AuctionBook {
    private static final String PREFIX = "auction.";
    private final DocumentStore store;
    private final Map<String, Auction> auctions = new ConcurrentHashMap<>();

    public AuctionBook(DocumentStore store) throws IOException {
        this.store = store;
        for (String key : store.keys()) {
            if (!key.startsWith(PREFIX)) continue;
            var json = store.load(key).orElse(null);
            if (json != null) auctions.put(key.substring(PREFIX.length()), Auction.fromJson(json));
        }
    }

    public Auction get(String id) { return id == null ? null : auctions.get(id); }

    public Collection<Auction> all() { return auctions.values(); }

    public synchronized void save(Auction auction) throws IOException {
        var json = auction.toJson();
        store.save(PREFIX + auction.id, json);
        if (!store.load(PREFIX + auction.id).orElseThrow().equals(json)) throw new IOException("Auction readback mismatch");
        auctions.put(auction.id, auction);
    }

    /** Applies the change to a copy of the latest state and stores it; if the change throws or the write fails, nothing has changed. */
    public synchronized Auction update(String id, Consumer<Auction> change) throws IOException {
        Auction current = auctions.get(id);
        if (current == null) throw new IllegalStateException("NO_AUCTION");
        Auction next = current.copy();
        change.accept(next);
        save(next);
        return next;
    }

    public synchronized void delete(String id) throws IOException {
        store.delete(PREFIX + id);
        auctions.remove(id);
    }
}
