package dev.toka.land.lease;

/** Pure rules of the auction: raises, the extension after a late bid, and the time display. */
public final class AuctionRules {
    public static final int HARD_MAX_HOURS = 168, DEFAULT_HOURS = 24;

    private AuctionRules() { }

    /** The fixed raise: the percent of the start price rounded up, at least 1 */
    public static long minRaise(long startPrice, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        long raw = Math.multiplyExact(startPrice, (long) clamped);
        return Math.max(1, (raw + 99) / 100);
    }

    /** A bid in the last {@code snipeMillis} moves the end to that long after the bid; an earlier bid leaves it */
    public static long endAfterBid(long endAt, long bidAt, long snipeMillis) {
        if (snipeMillis <= 0 || bidAt < endAt - snipeMillis) return endAt;
        return Math.max(endAt, bidAt + snipeMillis);
    }

    public static boolean hoursOk(long hours, int min, int max) { return hours >= Math.max(1, min) && hours <= Math.min(HARD_MAX_HOURS, max); }

    public static boolean priceOk(long price) { return price >= 1 && price <= Auction.MAX_PRICE; }

    /** hours:minutes:seconds, hours not limited to a day; nothing is left once the time has passed */
    public static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
