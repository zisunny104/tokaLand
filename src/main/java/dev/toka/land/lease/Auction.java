package dev.toka.land.lease;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The auction of one vacant sub-claim, stored as a document as it is. The highest bid is held back from the bidder (escrow) until the
 * auction is settled; an outbid bid is on the {@link #refunds} list until it has been given back. Times are milliseconds since the epoch.
 */
public final class Auction {
    public static final String RUNNING = "RUNNING", SETTLING = "SETTLING", ENDED = "ENDED", CANCELLED = "CANCELLED";
    /** what settlement decided: someone wins, nobody bid, or the claim is no longer the lessor's sub-claim so every bid goes back */
    public static final String WIN = "WIN", NOBID = "NOBID", VOID = "VOID";
    public static final int HISTORY = 50;
    public static final long MAX_PRICE = 1_000_000_000_000L;
    private static final Gson GSON = new Gson();

    /** One bid; {@code number} counts from 1 and goes into the operation ID of its payment */
    public static final class Bid {
        public String bidder, name;
        public long amount, at;
        public int number;
    }

    /** Money owed back for the withdrawal made under {@code op} */
    public static final class Refund {
        public String to, op;
        public long amount;
    }

    public String id, claimId, claimName, lessor, state = RUNNING, outcome, payee, leader, leaderName;
    public long startPrice, minRaise, price, startedAt, endAt, leaseAt, finishedAt;
    public int bids;
    /** whether the claim had an offer before the auction, so that it goes back to being one */
    public boolean hadOffer, paid;
    /** the lease terms of the winner; the rent is the winning bid */
    public Lease terms = new Lease();
    public Bid pending;
    public List<Bid> history = new ArrayList<>();
    public List<Refund> refunds = new ArrayList<>();

    public static String bidOperation(String id, int number) { return "auction:" + id + ":bid:" + number; }

    public String payOperation() { return "auction:" + id + ":pay"; }

    public boolean open(long now) { return RUNNING.equals(state) && now < endAt; }

    public boolean active() { return RUNNING.equals(state) || SETTLING.equals(state); }

    /** The least the next bid may be: the start price first, then the price plus the raise; above {@link #MAX_PRICE} there are no more bids. */
    public long minimumBid() {
        if (bids == 0) return startPrice;
        try { return Math.addExact(price, minRaise); } catch (ArithmeticException tooBig) { return Long.MAX_VALUE; }
    }

    public boolean canBid() { return minimumBid() <= MAX_PRICE; }

    /** Writes down the bid that is about to be paid; nothing else changes until the money is taken */
    public Bid reserve(String bidder, String name, long amount, long at) {
        Bid bid = new Bid();
        bid.bidder = bidder;
        bid.name = name;
        bid.amount = amount;
        bid.at = at;
        bid.number = bids + 1;
        pending = bid;
        return bid;
    }

    /** The pending bid has been paid: it leads now, the end may move, and the bid it beat is to be refunded (returned, or null for the first bid). */
    public Refund applyPending(long snipeMillis) {
        if (pending == null) throw new IllegalStateException("NO_PENDING_BID");
        Bid bid = pending;
        Refund back = null;
        if (leader != null) {
            back = new Refund();
            back.to = leader;
            back.amount = price;
            back.op = bidOperation(id, bids);
            refunds.add(back);
        }
        leader = bid.bidder;
        leaderName = bid.name;
        price = bid.amount;
        bids = bid.number;
        history.add(bid);
        while (history.size() > HISTORY) history.remove(0);
        endAt = AuctionRules.endAfterBid(endAt, bid.at, snipeMillis);
        pending = null;
        return back;
    }

    /** Puts the leader's held money on the refund list (the auction is called off, or the claim is not the lessor's any more) */
    public void refundLeader() {
        if (leader == null || paid) return;
        Refund back = new Refund();
        back.to = leader;
        back.amount = price;
        back.op = bidOperation(id, bids);
        refunds.add(back);
        leader = null;
        leaderName = null;
    }

    /** Money taken from bidders and not yet paid out or given back: the leading bid until it is paid to the lessor, plus the refunds still owed. */
    public long held() {
        long total = leader != null && !paid ? price : 0;
        for (Refund refund : refunds) total = Math.addExact(total, refund.amount);
        return total;
    }

    public static boolean allowed(String from, String to) {
        return RUNNING.equals(from) && (SETTLING.equals(to) || CANCELLED.equals(to)) || SETTLING.equals(from) && ENDED.equals(to);
    }

    public void to(String next) {
        if (!allowed(state, next)) throw new IllegalStateException("BAD_TRANSITION " + state + ">" + next);
        state = next;
    }

    public JsonObject toJson() { return GSON.toJsonTree(this).getAsJsonObject(); }

    public static Auction fromJson(JsonObject json) { return GSON.fromJson(json, Auction.class); }

    public Auction copy() { return fromJson(toJson()); }
}
