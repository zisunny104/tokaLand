package dev.toka.land.purchase;

import dev.toka.land.model.LandSelection;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LandPricingTest {
    private final LandPricing pricing = new LandPricing("money", 6.3, 73.1, 0.7);

    private static LandSelection part(boolean flat) {
        return new LandSelection("w", 0, 0, 0, 9, 19, 4, flat);
    }

    @Test public void volumePricingUsesWidthDepthAndHeight() {
        assertEquals(10 * 5 * 20, LandPricing.size(part(false)));
        assertEquals((long) (1000 * 6.3), pricing.quote(part(false), 1, 1));
    }

    @Test public void flatPricingIgnoresHeight() {
        assertEquals(50, LandPricing.size(part(true)));
        assertEquals((long) (50 * 73.1), pricing.quote(part(true), 1, 1));
    }

    @Test public void discountAndTaxApplyInOrderAndTruncateOnce() {
        assertEquals((long) (50 * 73.1 * 0.73 * 1.31), pricing.quote(part(true), 0.73, 1.31));
        assertEquals(0, pricing.quote(part(true), 1, 0));
    }

    @Test public void refundIgnoresDiscountsAndSumsParts() {
        assertEquals((long) (50 * 73.1 * 0.7), pricing.refund(List.of(part(true))));
        assertEquals(2 * (long) (1000 * 6.3 * 0.7), pricing.refund(List.of(part(false), part(false))));
    }

    @Test(expected = IllegalArgumentException.class) public void negativeAdjustmentIsRejected() {
        pricing.quote(part(true), -1, 1);
    }

    @Test(expected = IllegalArgumentException.class) public void oversizedSelectionIsRejected() {
        pricing.quote(new LandSelection("w", 0, 0, 0, 100000, 255, 100000, false), 1, 1);
    }
}
