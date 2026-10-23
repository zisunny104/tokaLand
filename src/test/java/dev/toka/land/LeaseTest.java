package dev.toka.land;

import static org.junit.Assert.*;

import dev.toka.land.lease.Lease;
import org.junit.Test;

public class LeaseTest {
    @Test public void totalIsRentTimesPeriodsWithTheDiscountOnlyBeyondTheThreshold() {
        Lease lease = new Lease();
        lease.rent = 100;
        assertEquals(400, lease.total(4));
        lease.discountAfter = 4;
        lease.discountPercent = 10;
        assertEquals(400, lease.total(4));
        assertEquals(450, lease.total(5));
    }

    @Test public void discountRoundsUpSoNothingIsFree() {
        Lease lease = new Lease();
        lease.rent = 7;
        lease.discountAfter = 1;
        lease.discountPercent = 50;
        assertEquals(11, lease.total(3)); // 21 at half is 10.5, rounded up
    }

    @Test public void aLeaseWithoutATermHasNoPeriodAndNoEnd() {
        Lease lease = new Lease();
        lease.periodDays = -1;
        assertTrue(lease.perpetual());
        assertEquals(0, lease.periodMillis());
        assertEquals(0, lease.shownEnd());
    }

    @Test public void theShownEndIsOneDayAfterWhatIsPaid() {
        Lease lease = new Lease();
        lease.paidUntil = 1_000_000_000L;
        assertEquals(lease.paidUntil + Lease.DAY, lease.shownEnd());
    }

    @Test public void aLeaseSurvivesStorage() {
        Lease lease = new Lease();
        lease.claimId = "x";
        lease.rent = 55;
        lease.state = Lease.LEASED;
        Lease back = Lease.fromJson(lease.toJson());
        assertEquals("x", back.claimId);
        assertEquals(55, back.rent);
        assertEquals(Lease.LEASED, back.state);
    }
}
