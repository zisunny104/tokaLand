package dev.toka.land.purchase;

import dev.toka.land.LandService;
import dev.toka.land.model.*;
import dev.toka.land.storage.DatasetRepository;
import dev.toka.storage.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

public class TreasuryFlowTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** Remembers who was named as payee or payer, so the tests can see where the money was meant to go. */
    private static final class Ledger implements PurchaseCoordinator.Ledger {
        UUID debitBeneficiary, refundBeneficiary, creditSource;
        long refundedAmount;
        public CompletableFuture<String> debit(UUID player, UUID beneficiary, String currency, long amount, String op) {
            debitBeneficiary = beneficiary;
            return CompletableFuture.completedFuture("OK");
        }
        public CompletableFuture<String> refund(UUID player, UUID beneficiary, String currency, long amount, String op) {
            refundBeneficiary = beneficiary;
            refundedAmount = amount;
            return CompletableFuture.completedFuture("OK");
        }
        public CompletableFuture<String> credit(UUID player, UUID source, String currency, long amount, String op) {
            creditSource = source;
            return CompletableFuture.completedFuture("OK");
        }
    }

    private static LandClaim claim(int x, ClaimDetails details) {
        UUID owner = UUID.randomUUID();
        return new LandClaim(UUID.randomUUID(), "c" + x, ClaimKind.PLAYER, "land", owner, null, false, false,
                List.of(new LandSelection("world", x, 0, 0, x + 3, 20, 3, false)), List.of(), AccessPolicy.privateDefault(), Map.of(), null, "Owner", details);
    }

    @Test public void thePriceGoesToTheTreasuryAndASurvivingRestartKeepsTheDestination() throws Exception {
        var folder = temporary.newFolder().toPath();
        UUID treasury = UUID.randomUUID();
        var claim = claim(0, new ClaimDetails(UUID.randomUUID(), "Owner", null, true, 60L, treasury));
        var ledger = new Ledger();
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, folder, 1)); var lands = new LandService(new DatasetRepository(store));
             var purchases = new PurchaseCoordinator(lands, store, () -> ledger)) {
            assertEquals("COMPLETED", purchases.purchase(claim, "money", 100, treasury).get().status());
            assertEquals(treasury, ledger.debitBeneficiary);
            // The same request with a different destination is a different payment, not a retry.
            assertEquals("CONFLICT", purchases.purchase(claim, "money", 100, UUID.randomUUID()).get().status());
            assertEquals("CONFLICT", purchases.purchase(claim, "money", 100, null).get().status());
        }
    }

    @Test public void aFailedCreationReturnsTheMoneyFromTheSameTreasury() throws Exception {
        UUID treasury = UUID.randomUUID();
        var ledger = new Ledger();
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1)); var lands = new LandService(new DatasetRepository(store));
             var purchases = new PurchaseCoordinator(lands, store, () -> ledger)) {
            var candidate = claim(0, new ClaimDetails(UUID.randomUUID(), "Owner", null, true, 50L, treasury));
            // An overlapping claim appears between payment and creation.
            var other = claim(1, ClaimDetails.privateDefaults());
            var racing = new PurchaseCoordinator.Ledger() {
                public CompletableFuture<String> debit(UUID p, UUID b, String c, long a, String op) { lands.create(other).join(); return ledger.debit(p, b, c, a, op); }
                public CompletableFuture<String> refund(UUID p, UUID b, String c, long a, String op) { return ledger.refund(p, b, c, a, op); }
            };
            try (var raced = new PurchaseCoordinator(lands, store, () -> racing)) {
                assertEquals("REFUNDED", raced.purchase(candidate, "money", 75, treasury).get().status());
            }
            assertEquals(treasury, ledger.refundBeneficiary);
            assertEquals(75, ledger.refundedAmount);
        }
    }

    @Test public void theRefundIsPaidFromTheAccountThatReceivedThePrice() throws Exception {
        UUID treasury = UUID.randomUUID();
        var ledger = new Ledger();
        var claim = claim(0, new ClaimDetails(UUID.randomUUID(), "Owner", null, true, 40L, treasury));
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1)); var lands = new LandService(new DatasetRepository(store));
             var sales = new SaleCoordinator(lands, store, () -> ledger)) {
            lands.create(claim).get();
            assertEquals("COMPLETED", sales.sell(claim, claim.ownerId(), "money", 40, treasury).get().status());
            assertEquals(treasury, ledger.creditSource);
        }
    }

    @Test public void refundValueAndSourceSurviveEditsAndStorage() {
        UUID treasury = UUID.randomUUID();
        var claim = claim(0, new ClaimDetails(UUID.randomUUID(), "Owner", new ClaimDetails.TeleportPoint("world", 1, 2, 1, 0, 0), true, 33L, treasury));
        var moved = ClaimEdits.teleport(claim, new ClaimDetails.TeleportPoint("world", 2, 2, 2, 0, 0));
        assertEquals(33L, (long) moved.details().refundValue());
        assertEquals(treasury, moved.details().refundSource());
        var elsewhere = ClaimEdits.inWorld(claim, "other");
        assertEquals(33L, (long) elsewhere.details().refundValue());
        var decoded = DatasetRepository.decode(DatasetRepository.encode(claim));
        assertEquals(claim, decoded);
    }

    @Test public void claimsFromBeforeRefundValuesStillLoad() {
        var claim = claim(0, ClaimDetails.privateDefaults());
        var json = DatasetRepository.encode(claim);
        json.getAsJsonObject("details").remove("refundValue");
        json.getAsJsonObject("details").remove("refundSource");
        var decoded = DatasetRepository.decode(json);
        assertNull(decoded.details().refundValue());
        assertNull(decoded.details().refundSource());
    }

    @Test(expected = IllegalArgumentException.class) public void aNegativeRefundValueIsRejected() {
        new ClaimDetails(null, null, null, false, -1L, null);
    }

    @Test public void settingTheRefundKeepsEverythingElseAndZeroMeansNothing() {
        UUID treasury = UUID.randomUUID();
        var claim = claim(0, new ClaimDetails(UUID.randomUUID(), "Owner", null, true, 33L, treasury));
        var none = ClaimEdits.refund(claim, 0);
        assertEquals(0L, (long) none.details().refundValue());
        assertEquals(treasury, none.details().refundSource());
        assertEquals(claim.id(), none.id());
        assertEquals(claim.selections(), none.selections());
    }
    @Test public void aTreasuryThatIsGoneRejectsThePurchaseBeforeAnyMoneyMoves() throws Exception {
        UUID treasury = UUID.randomUUID();
        var claim = claim(0, new ClaimDetails(UUID.randomUUID(), "Owner", null, true, 60L, treasury));
        PurchaseCoordinator.Ledger gone = new PurchaseCoordinator.Ledger() {
            public CompletableFuture<String> debit(UUID p, UUID b, String c, long a, String op) { return CompletableFuture.completedFuture("TREASURY_UNAVAILABLE"); }
            public CompletableFuture<String> refund(UUID p, UUID b, String c, long a, String op) { throw new AssertionError("nothing was paid"); }
        };
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1)); var lands = new LandService(new DatasetRepository(store));
             var purchases = new PurchaseCoordinator(lands, store, () -> gone)) {
            var outcome = purchases.purchase(claim, "money", 100, treasury).get();
            assertEquals("REJECTED", outcome.status());
            assertFalse(purchases.hasPending());
            assertTrue(lands.snapshot().index().all().isEmpty());
        }
    }
}
