package dev.toka.land;

import dev.toka.land.model.*;
import dev.toka.land.storage.DatasetRepository;
import dev.toka.storage.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.util.concurrent.ExecutionException;
import static org.junit.Assert.*;

public class LandServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static LandClaim claim(String name, ClaimKind kind, UUID parent, int minX, int maxX) {
        return new LandClaim(UUID.randomUUID(), name, kind, kind == ClaimKind.PLAYER ? "land" : "road", UUID.randomUUID(), parent, false, true,
                List.of(new LandSelection("w", minX, 0, 0, maxX, 10, 5, false)), List.of(), AccessPolicy.privateDefault(),
                Map.of(), null, null, ClaimDetails.privateDefaults());
    }

    private static String reason(ExecutionException error) {
        return error.getCause() instanceof LandService.ClaimConflict conflict ? conflict.getMessage() : String.valueOf(error.getCause());
    }

    @Test public void reshapingIntoAnotherPlayerClaimIsRejected() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1));
             var lands = new LandService(new DatasetRepository(store))) {
            var a = claim("a", ClaimKind.PLAYER, null, 0, 4);
            var b = claim("b", ClaimKind.PLAYER, null, 10, 14);
            lands.create(a).get();
            lands.create(b).get();
            var moved = ClaimEdits.reshape(a, new LandSelection("w", 0, 0, 0, 12, 10, 5, false));
            try { lands.edit(a, moved).get(); fail(); } catch (ExecutionException error) { assertEquals("SELECTION_CONFLICT", reason(error)); }
            var fine = ClaimEdits.reshape(a, new LandSelection("w", 0, 0, 0, 8, 10, 5, false));
            lands.edit(a, fine).get();
            assertEquals(fine, lands.snapshot().index().get(a.id()).orElseThrow());
        }
    }

    @Test public void facilityMayOverlapPlayerClaims() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1));
             var lands = new LandService(new DatasetRepository(store))) {
            lands.create(claim("home", ClaimKind.PLAYER, null, 0, 4)).get();
            lands.create(claim("road", ClaimKind.FACILITY, null, 2, 6)).get();
            assertEquals(2, lands.snapshot().index().all().size());
        }
    }

    @Test public void deleteAllRemovesTheSetAtomicallyAndKeepsOutsiderChildrenSafe() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1));
             var lands = new LandService(new DatasetRepository(store))) {
            var parent = claim("parent", ClaimKind.PLAYER, null, 0, 9);
            var child = claim("child", ClaimKind.PLAYER, parent.id(), 1, 2);
            var other = claim("other", ClaimKind.PLAYER, null, 20, 24);
            for (var c : List.of(parent, child, other)) lands.create(c).get();
            try { lands.deleteAll(List.of(parent)).get(); fail(); } catch (ExecutionException error) { assertEquals("CHILD_CLAIMS_EXIST", reason(error)); }
            assertEquals(3, lands.snapshot().index().all().size());
            lands.deleteAll(List.of(parent, child)).get();
            assertEquals(List.of(other), List.copyOf(lands.snapshot().index().all()));
        }
    }

    @Test public void deleteAllRejectsAClaimThatChangedSincePreview() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1));
             var lands = new LandService(new DatasetRepository(store))) {
            var a = claim("a", ClaimKind.PLAYER, null, 0, 4);
            lands.create(a).get();
            lands.edit(a, ClaimEdits.rename(a, "renamed")).get();
            try { lands.deleteAll(List.of(a)).get(); fail(); } catch (ExecutionException error) { assertEquals("CLAIM_CHANGED", reason(error)); }
        }
    }

    @Test public void renameWorldMovesClaimsAndTeleportTogether() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1));
             var lands = new LandService(new DatasetRepository(store))) {
            var home = claim("home", ClaimKind.PLAYER, null, 0, 4);
            var withTeleport = new LandClaim(home.id(), "home", home.kind(), home.type(), home.ownerId(), null, false, true, home.selections(),
                    List.of(), AccessPolicy.privateDefault(), Map.of(), null, null,
                    new ClaimDetails(null, null, new ClaimDetails.TeleportPoint("w", 1, 2, 1, 0, 0), false));
            lands.create(withTeleport).get();
            assertEquals(1, (int) lands.renameWorld("w", "w2").get());
            var after = lands.snapshot().index().get(home.id()).orElseThrow();
            assertEquals("w2", after.world());
            assertEquals("w2", after.details().teleport().world());
            assertEquals(withTeleport.selections().get(0).id(), after.selections().get(0).id());
            assertEquals(0, (int) lands.renameWorld("w", "w3").get());
        }
    }

    @Test public void renameWorldRefusesANameThatAlreadyHoldsClaims() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1));
             var lands = new LandService(new DatasetRepository(store))) {
            lands.create(claim("a", ClaimKind.PLAYER, null, 0, 4)).get();
            try { lands.renameWorld("other", "w").get(); fail(); } catch (ExecutionException error) { assertEquals("WORLD_NAME_IN_USE", reason(error)); }
        }
    }
    /** Fails the next save of the dataset, as a full disk or a locked file would. */
    private static final class FlakyStore implements DocumentStore {
        final DocumentStore delegate;boolean failNext;
        FlakyStore(DocumentStore delegate) { this.delegate = delegate; }
        public java.util.Optional<com.google.gson.JsonObject> load(String key) throws java.io.IOException { return delegate.load(key); }
        public void save(String key, com.google.gson.JsonObject data) throws java.io.IOException {
            if (failNext) { failNext = false; throw new java.io.IOException("disk full"); }
            delegate.save(key, data);
        }
        public void saveAll(java.util.Map<String, com.google.gson.JsonObject> values) throws java.io.IOException {
            if (failNext) { failNext = false; throw new java.io.IOException("disk full"); }
            delegate.saveAll(values);
        }
        public void delete(String key) throws java.io.IOException { delegate.delete(key); }
        public java.util.Set<String> keys() throws java.io.IOException { return delegate.keys(); }
        public java.util.SortedMap<String, com.google.gson.JsonObject> snapshot() throws java.io.IOException { return delegate.snapshot(); }
        public void close() {}
    }

    @Test public void aFailedWriteStopsLandOnlyUntilTheStoredDataIsReadBack() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1))) {
            var flaky = new FlakyStore(store);
            var lands = new LandService(new DatasetRepository(flaky));
            var first = claim("first", ClaimKind.PLAYER, null, 0, 4);
            lands.create(first).get();
            flaky.failNext = true;
            try { lands.create(claim("second", ClaimKind.PLAYER, null, 10, 14)).get(); fail(); } catch (ExecutionException expected) { /* the write failed */ }
            assertFalse(lands.healthy());
            assertTrue(lands.recover().get());
            assertTrue(lands.healthy());
            lands.create(claim("third", ClaimKind.PLAYER, null, 20, 24)).get();
            assertTrue(lands.snapshot().index().get(first.id()).isPresent());
            assertEquals(2, lands.snapshot().index().all().size());
        }
    }
}
