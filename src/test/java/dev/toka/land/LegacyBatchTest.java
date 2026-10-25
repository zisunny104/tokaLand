package dev.toka.land;

import dev.toka.land.model.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyBatchTest {
    private final List<Runnable> queued = new ArrayList<>();
    private final List<Map<String, UUID>> passes = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private CompletableFuture<Integer> manual;
    private boolean fail;

    private LegacyBatch batch() {
        return new LegacyBatch(map -> {
            passes.add(new HashMap<>(map));
            if (fail) return CompletableFuture.failedFuture(new IllegalStateException("x"));
            return manual != null ? manual : CompletableFuture.completedFuture(map.size());
        }, queued::add, (count, failure) -> { if (failure != null) failures.add(failure); });
    }

    @Test public void takeoversInOneWindowGiveOnePass() {
        var batch = batch();
        batch.add(Map.of("a", UUID.randomUUID()));
        batch.add(Map.of("b", UUID.randomUUID()));
        batch.add(Map.of("c", UUID.randomUUID()));
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(1, passes.size());
        assertEquals(Set.of("a", "b", "c"), passes.get(0).keySet());
        assertEquals(0, batch.pending());
        assertTrue(queued.isEmpty());
    }

    @Test public void pairArrivingDuringPassIsNotLost() {
        manual = new CompletableFuture<>();
        var batch = batch();
        batch.add(Map.of("a", UUID.randomUUID()));
        queued.remove(0).run();
        batch.add(Map.of("b", UUID.randomUUID()));
        assertTrue("no second flush while a pass runs", queued.isEmpty());
        var first = manual;
        manual = null;
        first.complete(1);
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(2, passes.size());
        assertEquals(Set.of("b"), passes.get(1).keySet());
        assertEquals(0, batch.pending());
    }

    @Test public void failedPassKeepsPairsForNextAdd() {
        fail = true;
        var batch = batch();
        batch.add(Map.of("a", UUID.randomUUID()));
        queued.remove(0).run();
        assertEquals(1, failures.size());
        assertEquals(1, batch.pending());
        assertTrue("no automatic retry loop", queued.isEmpty());
        fail = false;
        batch.add(Map.of("b", UUID.randomUUID()));
        queued.remove(0).run();
        assertEquals(Set.of("a", "b"), passes.get(1).keySet());
        assertEquals(0, batch.pending());
    }

    @Test public void batchedResultEqualsOneByOne() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        var grant = new AccessGrant(AccessSubject.legacyName("Bob"), ClaimPermission.values()[0], AccessDecision.values()[0]);
        var claim = new LandClaim(UUID.randomUUID(), "c", ClaimKind.PLAYER, "land", null, null, false, false,
                List.of(new LandSelection("w", 0, 0, 0, 4, 10, 5, false)), List.of(grant),
                AccessPolicy.privateDefault(), Map.of(), "Alice", null, ClaimDetails.privateDefaults());
        var together = LegacyNames.apply(List.of(claim), Map.of("Alice", u1, "Bob", u2));
        var step = LegacyNames.apply(LegacyNames.apply(List.of(claim), Map.of("Alice", u1)), Map.of("Bob", u2));
        assertNotSame(List.of(claim), together);
        assertEquals(together, step);
    }
}
