package dev.toka.land.lease;

/** Optional terms of an offer; a null field leaves the lease as it is. Limits live here so the command and the windows agree. */
public record LeaseTerms(Integer maxPeriods, Integer graceDays, Boolean autoRenew, Integer discountAfter, Integer discountPercent) {
    public static final int MAX_PERIODS = 120, MAX_GRACE = 90, MAX_DISCOUNT = 90;

    public static int clampPeriods(int value) { return Math.max(1, Math.min(MAX_PERIODS, value)); }

    public static int clampGrace(int value) { return Math.max(0, Math.min(MAX_GRACE, value)); }

    public static int clampDiscount(int value) { return Math.max(0, Math.min(MAX_DISCOUNT, value)); }

    public void applyTo(Lease lease) {
        if (maxPeriods != null) lease.maxPeriods = clampPeriods(maxPeriods);
        if (graceDays != null) lease.graceDays = clampGrace(graceDays);
        if (autoRenew != null) lease.autoRenew = autoRenew;
        if (discountAfter != null) lease.discountAfter = Math.max(0, discountAfter);
        if (discountPercent != null) lease.discountPercent = clampDiscount(discountPercent);
    }
}
