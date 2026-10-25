package dev.toka.land.lease;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The running auctions ordered by end time, so that only the earliest one is ever looked at. */
final class AuctionSchedule {
    private record Due(long at, String id) implements Comparable<Due> {
        @Override public int compareTo(Due other) {
            int byTime = Long.compare(at, other.at);
            return byTime != 0 ? byTime : id.compareTo(other.id);
        }
    }

    private final TreeSet<Due> order = new TreeSet<>();
    private final Map<String, Long> ends = new HashMap<>();

    /** Adds the auction, or moves its end */
    synchronized void set(String id, long endAt) {
        drop(id);
        order.add(new Due(endAt, id));
        ends.put(id, endAt);
    }

    synchronized void drop(String id) {
        Long end = ends.remove(id);
        if (end != null) order.remove(new Due(end, id));
    }

    /** The earliest auction whose end has come, removed from the schedule; null when none is due */
    synchronized String pollDue(long now) {
        if (order.isEmpty() || order.first().at() > now) return null;
        Due first = order.pollFirst();
        ends.remove(first.id());
        return first.id();
    }

    synchronized Long next() { return order.isEmpty() ? null : order.first().at(); }

    synchronized int size() { return order.size(); }

    synchronized List<String> ids() { return new ArrayList<>(ends.keySet()); }
}
