package dev.toka.land;

import java.util.concurrent.CompletableFuture;

/** Dataset operations for other plugins (world rename). */
final class DatasetAPI {
    private final LandService lands;
    private final Trading trading;

    DatasetAPI(LandService lands, Trading trading) {
        this.lands = lands;
        this.trading = trading;
    }

    /** For tokaWorlds after a world was renamed; refused while a transaction is unfinished, since it still names the old world. */
    CompletableFuture<Integer> renameWorld(String from, String to) {
        if (trading.hasPending()) throw new IllegalStateException("Unfinished land transactions must be recovered before renaming a world");
        return lands.renameWorld(from, to);
    }
}
