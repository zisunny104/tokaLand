package dev.toka.land.lease;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

public class AuctionTest {
    private static Auction running(long start, long raise, long end) {
        Auction auction = new Auction();
        auction.id = "a1";
        auction.lessor = "lessor";
        auction.startPrice = start;
        auction.minRaise = raise;
        auction.endAt = end;
        return auction;
    }

    private static void bid(Auction auction, String who, long amount, long at) {
        auction.reserve(who, who, amount, at);
        auction.applyPending(120_000);
    }

    @Test public void theRaiseIsFivePercentRoundedUpAndAtLeastOne() {
        assertEquals(5, AuctionRules.minRaise(100, 5));
        assertEquals(6, AuctionRules.minRaise(101, 5));
        assertEquals(1, AuctionRules.minRaise(1, 5));
        assertEquals(1, AuctionRules.minRaise(10, 0));
        assertEquals(100, AuctionRules.minRaise(100, 500));
        assertEquals(50_000_000_000L, AuctionRules.minRaise(Auction.MAX_PRICE, 5));
    }

    @Test public void theFirstBidIsTheStartPriceThenPricePlusRaise() {
        Auction auction = running(100, 5, 1_000_000);
        assertEquals(100, auction.minimumBid());
        bid(auction, "x", 100, 0);
        assertEquals(105, auction.minimumBid());
        bid(auction, "y", 130, 0);
        assertEquals(135, auction.minimumBid());
    }

    @Test public void thePriceLimitStopsBidsWithoutOverflow() {
        Auction auction = running(Auction.MAX_PRICE, 5, 1_000_000);
        assertTrue(auction.canBid());
        bid(auction, "x", Auction.MAX_PRICE, 0);
        assertFalse(auction.canBid());
        auction.price = Long.MAX_VALUE - 1;
        assertEquals(Long.MAX_VALUE, auction.minimumBid());
    }

    @Test public void aLateBidMovesTheEndToTwoMinutesAfterIt() {
        long end = 10_000_000, snipe = 120_000;
        assertEquals(end, AuctionRules.endAfterBid(end, end - snipe - 1, snipe));
        assertEquals(end + 1_000, AuctionRules.endAfterBid(end, end - snipe + 1_000, snipe));
        assertEquals(end + snipe, AuctionRules.endAfterBid(end, end, snipe));
        assertEquals(end, AuctionRules.endAfterBid(end, end - 10, 0));
        Auction auction = running(100, 5, end);
        auction.reserve("x", "x", 100, end - 30_000);
        auction.applyPending(snipe);
        assertEquals(end - 30_000 + snipe, auction.endAt);
    }

    @Test public void theScheduleGivesTheEarliestDueAuctionOnly() {
        AuctionSchedule schedule = new AuctionSchedule();
        schedule.set("c", 300);
        schedule.set("a", 100);
        schedule.set("b", 200);
        assertEquals(Long.valueOf(100), schedule.next());
        assertNull(schedule.pollDue(99));
        assertEquals("a", schedule.pollDue(250));
        assertEquals("b", schedule.pollDue(250));
        assertNull(schedule.pollDue(250));
        schedule.set("c", 900);
        assertNull(schedule.pollDue(300));
        schedule.drop("c");
        assertEquals(0, schedule.size());
        schedule.set("x", 5);
        schedule.set("y", 5);
        assertEquals("x", schedule.pollDue(5));
        assertEquals("y", schedule.pollDue(5));
    }

    @Test public void stateTransitionsOnlyGoForward() {
        assertTrue(Auction.allowed(Auction.RUNNING, Auction.SETTLING));
        assertTrue(Auction.allowed(Auction.RUNNING, Auction.CANCELLED));
        assertTrue(Auction.allowed(Auction.SETTLING, Auction.ENDED));
        assertFalse(Auction.allowed(Auction.SETTLING, Auction.CANCELLED));
        assertFalse(Auction.allowed(Auction.ENDED, Auction.RUNNING));
        assertFalse(Auction.allowed(Auction.CANCELLED, Auction.SETTLING));
        Auction auction = running(1, 1, 1);
        auction.to(Auction.SETTLING);
        try { auction.to(Auction.RUNNING); fail(); } catch (IllegalStateException expected) { /* refused */ }
        assertEquals(Auction.SETTLING, auction.state);
    }

    @Test public void anOutbidBidIsOnTheRefundListWithTheOperationOfItsPayment() {
        Auction auction = running(100, 5, 1_000_000);
        bid(auction, "x", 100, 0);
        assertTrue(auction.refunds.isEmpty());
        auction.reserve("y", "y", 105, 0);
        Auction.Refund back = auction.applyPending(0);
        assertEquals("x", back.to);
        assertEquals(100, back.amount);
        assertEquals("auction:a1:bid:1", back.op);
        assertEquals("y", auction.leader);
        assertEquals(2, auction.bids);
        assertEquals(1, auction.refunds.size());
    }

    @Test public void moneyHeldIsTheLeaderPlusRefundsOwedAndNothingIsLost() {
        Auction auction = running(100, 5, 1_000_000);
        long taken = 0;
        long[] amounts = {100, 105, 111, 200};
        String[] who = {"x", "y", "x", "z"};
        for (int i = 0; i < amounts.length; i++) {
            bid(auction, who[i], amounts[i], 0);
            taken += amounts[i];
            long returned = 0;
            assertEquals(taken - returned, auction.held());
        }
        assertEquals(4, auction.bids);
        // every earlier bid is owed back, the leader's 200 stays held
        assertEquals(taken, auction.held());
        long owed = auction.refunds.stream().mapToLong(r -> r.amount).sum();
        assertEquals(100 + 105 + 111, owed);
        auction.refunds.remove(0);
        assertEquals(taken - 100, auction.held());
        // paying the lessor takes the leader's bid out of what is held
        auction.paid = true;
        assertEquals(105 + 111, auction.held());
    }

    @Test public void callingOffPutsTheLeaderOnTheRefundListOnce() {
        Auction auction = running(100, 5, 1_000_000);
        bid(auction, "x", 100, 0);
        auction.refundLeader();
        assertNull(auction.leader);
        assertEquals(1, auction.refunds.size());
        assertEquals(100, auction.held());
        auction.refundLeader();
        assertEquals(1, auction.refunds.size());
    }

    @Test public void theBidHistoryKeepsTheLastFifty() {
        Auction auction = running(1, 1, 1_000_000);
        for (int i = 1; i <= 60; i++) bid(auction, "p" + i, i, 0);
        assertEquals(Auction.HISTORY, auction.history.size());
        assertEquals(11, auction.history.get(0).number);
        assertEquals(60, auction.history.get(49).number);
    }

    @Test public void aPendingBidChangesNothingUntilItIsApplied() {
        Auction auction = running(100, 5, 1_000_000);
        auction.reserve("x", "x", 100, 0);
        assertEquals(0, auction.bids);
        assertNull(auction.leader);
        assertEquals(1, auction.pending.number);
        assertEquals(0, auction.held());
    }

    @Test public void anAuctionSurvivesStorage() {
        Auction auction = running(100, 5, 1_000_000);
        bid(auction, "x", 100, 0);
        auction.reserve("y", "y", 105, 0);
        Auction back = Auction.fromJson(auction.toJson());
        assertEquals(100, back.price);
        assertEquals("x", back.leader);
        assertEquals(105, back.pending.amount);
        assertEquals(1, back.history.size());
        assertEquals(auction.terms.periodDays, back.terms.periodDays);
    }

    @Test public void limitsOnHoursAndPrice() {
        assertTrue(AuctionRules.hoursOk(24, 1, 168));
        assertFalse(AuctionRules.hoursOk(0, 1, 168));
        assertFalse(AuctionRules.hoursOk(169, 1, 1000));
        assertFalse(AuctionRules.hoursOk(25, 1, 24));
        assertFalse(AuctionRules.priceOk(0));
        assertFalse(AuctionRules.priceOk(Auction.MAX_PRICE + 1));
        assertTrue(AuctionRules.priceOk(Auction.MAX_PRICE));
        assertEquals("25:00:05", AuctionRules.clock(90_005_000L));
        assertEquals("0:00:00", AuctionRules.clock(-5));
    }

    @Test public void everyAuctionTextKeyUsedInCodeExists() throws IOException {
        Set<String> zh = new LinkedHashSet<>();
        List<String> path = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/main/resources/lang/zh-TW.yml"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length(), colon = line.indexOf(':', indent);
            while (path.size() > indent / 2) path.remove(path.size() - 1);
            path.add(line.substring(indent, colon));
            if (!line.substring(colon + 1).isBlank()) zh.add(String.join(".", path));
        }
        Pattern literal = Pattern.compile("\"((?:auction|aw)\\.[A-Za-z.]+|log\\.auction[A-Za-z]+)\"");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher found = literal.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (found.find()) if (!zh.contains(found.group(1))) missing.add(found.group(1));
            }
        }
        // keys built from a state name are checked by their prefix
        missing.removeIf(key -> key.equals("auction.state.") && zh.contains("auction.state.RUNNING"));
        assertTrue("missing: " + missing, missing.isEmpty());
    }
}
