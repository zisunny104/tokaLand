package dev.toka.land;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Collects announced (old name, account) pairs and applies everything collected so far in one pass. Adding is only a map put plus,
 * at most, one scheduled flush; pairs that arrive during a pass wait for the next one. A pair is dropped only after a pass that
 * contained it succeeded, so a failed pass is retried by the next add.
 */
final class LegacyBatch {
    private final Map<String, UUID> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Function<Map<String, UUID>, CompletableFuture<Integer>> pass;
    private final Consumer<Runnable> later;
    private final BiConsumer<Integer, Throwable> done;

    /** @param later runs the given flush after the configured delay */
    LegacyBatch(Function<Map<String, UUID>, CompletableFuture<Integer>> pass, Consumer<Runnable> later, BiConsumer<Integer, Throwable> done) {
        this.pass = pass;
        this.later = later;
        this.done = done;
    }

    void add(Map<String, UUID> resolved) {
        if (resolved.isEmpty()) return;
        pending.putAll(resolved);
        trigger();
    }

    int pending() { return pending.size(); }

    private void trigger() {
        if (!pending.isEmpty() && scheduled.compareAndSet(false, true)) later.accept(this::flush);
    }

    private void flush() {
        Map<String, UUID> taken = new HashMap<>(pending);
        if (taken.isEmpty()) { scheduled.set(false); trigger(); return; }
        CompletableFuture<Integer> result;
        try { result = pass.apply(taken); }
        catch (RuntimeException failure) { result = CompletableFuture.failedFuture(failure); }
        result.whenComplete((count, failure) -> {
            if (failure == null) taken.forEach(pending::remove); // remove(key, value) keeps a newer announcement for the same name
            try { done.accept(count, failure); }
            finally {
                scheduled.set(false);
                if (failure == null) trigger(); // a failed pass waits for the next add, so a broken store is not hammered
            }
        });
    }
}
