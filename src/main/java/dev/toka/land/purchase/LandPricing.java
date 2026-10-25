package dev.toka.land.purchase;

import dev.toka.land.model.LandSelection;
import java.util.Collection;

/**
 * Base land prices. Discounts and taxes are not decided here: a provider supplies them per purchase
 * (tokaRegions does). Amounts are whole units, truncated like the legacy rules.
 */
public record LandPricing(String currency, double perBlock, double perBlockFlat, double refundRate) {
    public LandPricing {
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("pricing.currency is required");
        for (double value : new double[]{perBlock, perBlockFlat, refundRate})
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("pricing values must be finite and not negative");
        if (refundRate > 1) throw new IllegalArgumentException("pricing.refund-rate cannot exceed 1");
    }

    /** Blocks priced: the footprint, times the height unless the selection ignores height. */
    public static long size(LandSelection part) {
        long area = Math.multiplyExact((long) part.maxX() - part.minX() + 1, (long) part.maxZ() - part.minZ() + 1);
        return part.ignoreHeight() ? area : Math.multiplyExact(area, (long) part.maxY() - part.minY() + 1);
    }

    /** Blocks of the footprint. */
    public static long area(LandSelection part) {
        return Math.multiplyExact((long) part.maxX() - part.minX() + 1, (long) part.maxZ() - part.minZ() + 1);
    }

    /** Blocks of the whole box, from the lowest to the highest selected layer. */
    public static long volume(LandSelection part) {
        return Math.multiplyExact(area(part), (long) part.maxY() - part.minY() + 1);
    }

    /** What the footprint alone would cost, with the provider's discount and tax; shown next to the volume price. */
    public long quoteFlat(LandSelection part, double offer, double tax) {
        return (long) (area(part) * perBlockFlat * offer * tax);
    }

    /** What the whole box would cost, with the provider's discount and tax. */
    public long quoteVolume(LandSelection part, double offer, double tax) {
        return (long) (volume(part) * perBlock * offer * tax);
    }

    private double base(LandSelection part) {
        long size = size(part);
        if (size > Integer.MAX_VALUE) throw new IllegalArgumentException("SELECTION_TOO_LARGE");
        return size * (part.ignoreHeight() ? perBlockFlat : perBlock);
    }

    /** Price after the provider's discount factor (offer) and tax factor, applied in that order. */
    public long quote(LandSelection part, double offer, double tax) {
        if (!Double.isFinite(offer) || !Double.isFinite(tax) || offer < 0 || tax < 0) throw new IllegalArgumentException("INVALID_ADJUSTMENT");
        return (long) (base(part) * offer * tax);
    }

    /** Refund for deleting a claim, from its geometry only; discounts and taxes paid are not refunded. */
    public long refund(Collection<LandSelection> parts) {
        long total = 0;
        for (LandSelection part : parts) total = Math.addExact(total, (long) (base(part) * refundRate));
        return total;
    }
}
