package dev.toka.land.lease;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The terms and the state of the letting of one sub-claim. Plain fields so that it is stored as a document as it is.
 * Times are milliseconds since the epoch; {@code periodDays} of -1 means there is no term at all (one payment, no end).
 */
public final class Lease {
    public static final String VACANT = "VACANT", PENDING = "PENDING", RESERVED = "RESERVED", LEASED = "LEASED", GRACE = "GRACE";
    public static final long DAY = 86_400_000L;
    private static final Gson GSON = new Gson();

    public String claimId, lessor, tenant, tenantName, state = VACANT, operation;
    public long rent;
    public int periodDays = 90, maxPeriods = 12, graceDays = 7, discountAfter, discountPercent;
    public boolean autoRenew = true;
    /** start of the term, end of what has been paid, the last day it may run to (0: none), when the grace ends, the last try to charge */
    public long startAt, paidUntil, endCap, graceUntil, lastTry;
    /** what a pending rent is for */
    public int pendingPeriods;
    public long pendingAmount;

    public boolean perpetual() { return periodDays < 0; }

    public long periodMillis() { return perpetual() ? 0 : periodDays * DAY; }

    /** The price of {@code periods} periods: rent times periods, with the discount for prepaying more than {@code discountAfter}, rounded up */
    public long total(int periods) {
        long plain = Math.multiplyExact(rent, (long) periods);
        if (discountAfter > 0 && discountPercent > 0 && periods > discountAfter) return (plain * (100 - discountPercent) + 99) / 100;
        return plain;
    }

    /** The day the term is shown to end: what is paid up to, plus one day of slack */
    public long shownEnd() { return perpetual() ? 0 : paidUntil + DAY; }

    public JsonObject toJson() { return GSON.toJsonTree(this).getAsJsonObject(); }

    public static Lease fromJson(JsonObject json) { return GSON.fromJson(json, Lease.class); }

    public Lease copy() { return fromJson(toJson()); }
}
