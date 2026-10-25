package dev.toka.land.lease;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import dev.toka.land.Main;
import dev.toka.land.purchase.PurchaseCoordinator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Auctions of vacant sub-claims. A bid is written down as pending, its money is taken once (operation ID from the auction and the bid
 * number) and held in the auction document; the bid it beats is given back by a refund entry that stays until the economy confirms it.
 * At the end the leading bid is paid to the lessor and the claim is leased to the winner. Every step is recorded before the next
 * one, so after a stop {@link #tick()} finishes what was left without taking or paying anything twice.
 */
public final class Auctions {
    private static final long RETRY_MILLIS = 5_000L, SWEEP_TICKS = 60, KEEP_MILLIS = 30L * Lease.DAY, RECENT_MILLIS = 7L * Lease.DAY;
    private final Main plugin;
    private final Leases leases;
    private final AuctionBook book;
    private final AuctionSchedule schedule = new AuctionSchedule();
    /** auctions with something still to do besides waiting for the end: a pending bid, refunds, settlement, or an end that has come */
    private final Set<String> work = ConcurrentHashMap.newKeySet();
    /** auctions with a step in flight, so that one auction never has two */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> cooling = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private boolean reconciled;
    private int sweep;

    public Auctions(Main plugin, Leases leases, AuctionBook book) {
        this.plugin = plugin;
        this.leases = leases;
        this.book = book;
        long now = System.currentTimeMillis();
        for (Auction auction : List.copyOf(book.all())) {
            if (!auction.active() && auction.refunds.isEmpty() && auction.finishedAt != 0 && now - auction.finishedAt > KEEP_MILLIS) {
                try { book.delete(auction.id); } catch (IOException failure) { /* kept until the next start */ }
                continue;
            }
            track(auction);
        }
    }

    /** After this nothing is written, so work in flight cannot touch a store that is closing. */
    public void close() { closed = true; }

    // ---- settings and small helpers ----

    private boolean enabled() { return plugin.getConfig().getBoolean("lease.auction.enabled", true); }

    private int minHours() { return plugin.getConfig().getInt("lease.auction.min-hours", 1); }

    private int maxHours() { return plugin.getConfig().getInt("lease.auction.max-hours", AuctionRules.HARD_MAX_HOURS); }

    private long snipeMillis() { return Math.max(0, plugin.getConfig().getInt("lease.auction.anti-snipe-seconds", 120)) * 1000L; }

    private String currency() { return plugin.getConfig().getString("lease.currency", "money"); }

    private String text(CommandSender who, String key, Object... args) { return plugin.texts().text(who, key, args); }

    private PurchaseCoordinator.Ledger ledger() { return plugin.trading() == null ? null : plugin.trading().ledger(); }

    private static String root(Throwable error) { return Leases.rootMessage(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error); }

    private boolean ready(Player player) {
        if (!player.hasPermission("tokaland.use")) { player.sendMessage(text(player, "command.denied")); return false; }
        if (!enabled()) { player.sendMessage(text(player, "auction.disabled")); return false; }
        if (!Main.ownsLand()) { player.sendMessage(text(player, "auction.unavailable")); return false; }
        return true;
    }

    public Auction get(String id) { return book.get(id); }

    /** The running auctions, the one that ends first at the top */
    public List<Auction> running() {
        return book.all().stream().filter(a -> Auction.RUNNING.equals(a.state)).sorted(Comparator.comparingLong((Auction a) -> a.endAt).thenComparing(a -> a.id)).toList();
    }

    /** Auctions the player leads, has money held in, or took part in lately */
    public List<Auction> mine(UUID player) {
        String me = player.toString();
        long now = System.currentTimeMillis();
        List<Auction> found = new ArrayList<>();
        for (Auction a : book.all()) {
            boolean leads = me.equals(a.leader) || a.pending != null && me.equals(a.pending.bidder) || a.refunds.stream().anyMatch(r -> me.equals(r.to));
            boolean took = a.history.stream().anyMatch(b -> me.equals(b.bidder));
            if (leads || took && (a.active() || now - a.finishedAt < RECENT_MILLIS)) found.add(a);
        }
        found.sort(Comparator.comparing((Auction a) -> !a.active()).thenComparing(Comparator.comparingLong((Auction a) -> a.endAt).reversed()));
        return found;
    }

    /** By auction ID, a unique start of one (six or more characters), or the name of a claim: the one that is still going, else the latest */
    public Auction find(String target) {
        if (target == null || target.isBlank()) return null;
        String key = target.strip().toLowerCase(Locale.ROOT);
        Auction byId = book.get(key);
        if (byId != null) return byId;
        if (key.length() >= 6) {
            List<Auction> starts = book.all().stream().filter(a -> a.id.startsWith(key)).toList();
            if (starts.size() == 1) return starts.get(0);
        }
        return book.all().stream().filter(a -> a.claimName != null && a.claimName.equalsIgnoreCase(target.strip()))
                .min(Comparator.comparing((Auction a) -> !a.active()).thenComparing(Comparator.comparingLong((Auction a) -> a.endAt).reversed())).orElse(null);
    }

    private boolean isLessor(Auction a, UUID who) { return a.lessor.equals(who.toString()); }

    /** The claim still is a vacant sub-claim of a claim the lessor owns */
    private boolean stillValid(Auction a) {
        var sub = leases.claim(UUID.fromString(a.claimId)).orElse(null);
        if (sub == null || sub.parentClaimId() == null) return false;
        var parent = leases.claim(sub.parentClaimId()).orElse(null);
        Lease lease = leases.leaseOf(sub.id());
        boolean owned = parent != null && leases.ownerOf(parent).map(UUID::toString).filter(a.lessor::equals).isPresent();
        return owned && (lease == null || Lease.VACANT.equals(lease.state));
    }

    private void track(Auction a) {
        if (Auction.RUNNING.equals(a.state)) schedule.set(a.id, a.endAt); else schedule.drop(a.id);
        if (a.pending != null || !a.refunds.isEmpty() || Auction.SETTLING.equals(a.state)) work.add(a.id);
    }

    private Auction update(String id, Consumer<Auction> change) {
        if (closed) throw new CompletionException(new IllegalStateException("CLOSED"));
        try {
            Auction next = book.update(id, change);
            track(next);
            return next;
        } catch (IOException failure) { throw new CompletionException(failure); }
    }

    private CompletableFuture<String> money(Function<PurchaseCoordinator.Ledger, CompletableFuture<String>> step) {
        var ledger = ledger();
        if (ledger == null) return CompletableFuture.completedFuture("UNAVAILABLE");
        try { return step.apply(ledger).orTimeout(30, TimeUnit.SECONDS).handle((status, fault) -> fault != null || status == null ? "FAILED" : status); }
        catch (RuntimeException failure) { return CompletableFuture.completedFuture("FAILED"); }
    }

    private boolean cooling(String key) {
        Long at = cooling.get(key);
        return at != null && System.currentTimeMillis() - at < RETRY_MILLIS;
    }

    private void tell(String player, String key, Object... args) { if (player != null) leases.tell(UUID.fromString(player), key, args); }

    // ---- starting and cancelling ----

    /** {@code hours}, {@code days} and {@code terms} may be null: the default length, the period of the offer (or the server's), and the terms of the offer (or the server's) */
    public void start(Player player, String subName, long startPrice, Integer hours, Integer days, LeaseTerms terms) {
        if (!ready(player)) return;
        var sub = leases.named(subName).orElse(null);
        if (sub == null) { player.sendMessage(text(player, "lease.noClaim", subName)); return; }
        if (sub.parentClaimId() == null) { player.sendMessage(text(player, "lease.notSub")); return; }
        var parent = leases.claim(sub.parentClaimId()).orElse(null);
        UUID lessor = parent == null ? null : leases.ownerOf(parent).orElse(null);
        if (lessor == null || !lessor.equals(player.getUniqueId()) && !player.hasPermission("tokaland.admin")) { player.sendMessage(text(player, "lease.notOwner")); return; }
        Lease existing = leases.leaseOf(sub.id());
        if (existing != null && existing.auctionId != null) { player.sendMessage(text(player, "auction.running")); return; }
        if (existing != null && !Lease.VACANT.equals(existing.state)) { player.sendMessage(text(player, "lease.busy")); return; }
        if (!AuctionRules.priceOk(startPrice)) { player.sendMessage(text(player, "auction.badNumber", LeaseInput.money(Auction.MAX_PRICE))); return; }
        long length = hours == null ? Math.max(minHours(), Math.min(maxHours(), AuctionRules.DEFAULT_HOURS)) : hours;
        if (!AuctionRules.hoursOk(length, minHours(), maxHours())) { player.sendMessage(text(player, "auction.badHours", Math.max(1, minHours()), Math.min(AuctionRules.HARD_MAX_HOURS, maxHours()))); return; }
        var config = plugin.getConfig();
        int period = days != null ? days : existing != null ? existing.periodDays : config.getInt("lease.period-days", 90);
        if (period == 0 || period < -1 || period > 3650 || period == -1 && !config.getBoolean("lease.allow-perpetual", false)) {
            player.sendMessage(text(player, period == -1 ? "lease.perpetualOff" : "lease.badNumber"));
            return;
        }
        Lease base = new Lease();
        if (existing != null) {
            base.maxPeriods = existing.maxPeriods;
            base.graceDays = existing.graceDays;
            base.autoRenew = existing.autoRenew;
            base.discountAfter = existing.discountAfter;
            base.discountPercent = existing.discountPercent;
        } else {
            base.maxPeriods = config.getInt("lease.max-periods", 12);
            base.graceDays = config.getInt("lease.grace-days", 7);
        }
        if (terms != null) terms.applyTo(base);
        base.periodDays = period;
        base.claimId = sub.id().toString();
        base.lessor = lessor.toString();
        base.rent = startPrice;
        long now = System.currentTimeMillis();
        Auction auction = new Auction();
        auction.id = UUID.randomUUID().toString();
        auction.claimId = sub.id().toString();
        auction.claimName = sub.name();
        auction.lessor = lessor.toString();
        auction.startPrice = startPrice;
        auction.minRaise = AuctionRules.minRaise(startPrice, config.getInt("lease.auction.min-raise-percent", 5));
        auction.startedAt = now;
        auction.endAt = now + length * 3_600_000L;
        auction.terms = base;
        auction.hadOffer = existing != null;
        try {
            book.save(auction);
            leases.lockAuction(sub, auction);
        } catch (IOException | RuntimeException failure) {
            try { book.delete(auction.id); } catch (IOException ignored) { /* nothing was locked */ }
            player.sendMessage(text(player, "lease.failed", Leases.rootMessage(failure)));
            return;
        }
        track(auction);
        player.sendMessage(text(player, "auction.started", sub.name(), LeaseInput.money(startPrice), LeaseInput.money(auction.minRaise), length));
    }

    /** The lessor (or an admin) calls the auction off; only while nobody has bid */
    public void cancel(Player player, String target) {
        if (!ready(player)) return;
        Auction a = find(target);
        if (a == null || !Auction.RUNNING.equals(a.state)) { player.sendMessage(text(player, "auction.notFound")); return; }
        if (!isLessor(a, player.getUniqueId()) && !player.hasPermission("tokaland.admin")) { player.sendMessage(text(player, "lease.notOwner")); return; }
        if (a.bids > 0 || a.pending != null) { player.sendMessage(text(player, "auction.hasBids")); return; }
        try {
            update(a.id, x -> {
                if (x.bids > 0 || x.pending != null) throw new IllegalStateException("HAS_BIDS");
                x.to(Auction.CANCELLED);
                x.finishedAt = System.currentTimeMillis();
            });
            leases.unlockAuction(a);
        } catch (RuntimeException failure) { player.sendMessage(text(player, "auction.hasBids")); return; }
        player.sendMessage(text(player, "auction.cancelled", a.claimName));
    }

    /** Called off by the system: every bid goes back. A bid still being paid is finished first, so this waits for it. */
    private void abort(String id, String reason) {
        Auction done = update(id, x -> {
            if (!Auction.RUNNING.equals(x.state) || x.pending != null) throw new IllegalStateException("NOT_NOW");
            x.refundLeader();
            x.to(Auction.CANCELLED);
            x.finishedAt = System.currentTimeMillis();
        });
        leases.unlockAuction(done);
        plugin.texts().info("log.auctionCancelled", done.claimName, reason);
        tell(done.lessor, "auction.invalidLessor", done.claimName);
        tell(done.leader, "auction.invalidBidder", done.claimName);
    }

    // ---- bidding ----

    public void bid(Player player, String target, long amount) {
        if (!ready(player)) return;
        Auction a = find(target);
        long now = System.currentTimeMillis();
        if (a == null) { player.sendMessage(text(player, "auction.notFound")); return; }
        UUID me = player.getUniqueId();
        if (!a.open(now)) { player.sendMessage(text(player, "auction.closed")); return; }
        if (isLessor(a, me)) { player.sendMessage(text(player, "auction.ownLand")); return; }
        if (me.toString().equals(a.leader)) { player.sendMessage(text(player, "auction.leading")); return; }
        if (a.pending != null || busy.contains(a.id)) { player.sendMessage(text(player, "auction.busy")); return; }
        if (!a.canBid()) { player.sendMessage(text(player, "auction.noMore")); return; }
        long least = a.minimumBid();
        if (amount < least || amount > Auction.MAX_PRICE) { player.sendMessage(text(player, "auction.tooLow", LeaseInput.money(least))); return; }
        if (!stillValid(a)) {
            player.sendMessage(text(player, "auction.invalid"));
            if (a.bids == 0) try { abort(a.id, "INVALID"); } catch (RuntimeException ignored) { /* the sweep tries again */ }
            return;
        }
        var ledger = ledger();
        if (ledger == null) { player.sendMessage(text(player, "auction.noEconomy")); return; }
        if (!"OK".equals(ledger.covers(me, currency(), amount))) { player.sendMessage(text(player, "auction.insufficient", LeaseInput.money(amount))); return; }
        if (!busy.add(a.id)) { player.sendMessage(text(player, "auction.busy")); return; }
        final int seen = a.bids;
        try {
            update(a.id, x -> {
                if (!x.open(now) || x.pending != null || x.bids != seen || me.toString().equals(x.leader)) throw new IllegalStateException("STALE");
                x.reserve(me.toString(), player.getName(), amount, now);
            });
        } catch (RuntimeException failure) {
            busy.remove(a.id);
            player.sendMessage(text(player, "auction.stale"));
            return;
        }
        player.sendMessage(text(player, "auction.bidding", LeaseInput.money(amount)));
        runBid(a.id).whenComplete((ignored, fault) -> {
            busy.remove(a.id);
            if (fault != null) plugin.texts().warn("log.auctionFailed", a.id, root(fault));
        });
    }

    /** Takes the money of the pending bid (once), then makes it the leading bid. A refusal for good drops the bid; an unclear answer leaves it pending to be tried again with the same operation ID. */
    private CompletableFuture<Void> runBid(String id) {
        Auction a = book.get(id);
        if (a == null || a.pending == null) return CompletableFuture.completedFuture(null);
        Auction.Bid bid = a.pending;
        UUID bidder = UUID.fromString(bid.bidder);
        return money(ledger -> ledger.debit(bidder, null, currency(), bid.amount, Auction.bidOperation(id, bid.number))).thenAccept(status -> {
            if (status.equals("OK")) {
                Auction.Refund[] beaten = new Auction.Refund[1];
                Auction now = update(id, x -> { if (x.pending != null && x.pending.number == bid.number) beaten[0] = x.applyPending(snipeMillis()); });
                if (now.bids != bid.number) return;
                tell(bid.bidder, "auction.bidPlaced", now.claimName, LeaseInput.money(bid.amount));
                if (beaten[0] != null) tell(beaten[0].to, "auction.outbid", now.claimName, LeaseInput.money(beaten[0].amount), LeaseInput.money(bid.amount));
                tell(now.lessor, "auction.newBid", now.claimName, bid.name, LeaseInput.money(bid.amount));
            } else if (!status.equals("FAILED") && !status.equals("UNAVAILABLE")) {
                update(id, x -> { if (x.pending != null && x.pending.number == bid.number) x.pending = null; });
                tell(bid.bidder, status.equals("INSUFFICIENT_FUNDS") ? "auction.insufficient" : "auction.failed", status.equals("INSUFFICIENT_FUNDS") ? LeaseInput.money(bid.amount) : status);
            } else cooling.put(id + ":bid", System.currentTimeMillis());
        });
    }

    // ---- upkeep ----

    /** Called every second: moves auctions whose end has come to the work list, then does the next step of each auction that has any. Nothing else is scanned except the slow sweep. */
    public void tick() {
        if (closed) return;
        if (!reconciled) { reconcile(); reconciled = true; }
        long now = System.currentTimeMillis();
        String due;
        while ((due = schedule.pollDue(now)) != null) work.add(due);
        for (String id : List.copyOf(work)) resume(id);
        if (++sweep >= SWEEP_TICKS) { sweep = 0; sweepInvalid(); }
    }

    /** After a start the lease of a running auction is held again and that of a finished one is let go, in case a stop came between two writes */
    private void reconcile() {
        for (Auction a : List.copyOf(book.all())) {
            try {
                var sub = leases.claim(UUID.fromString(a.claimId)).orElse(null);
                if (Auction.RUNNING.equals(a.state) && sub != null) leases.lockAuction(sub, a);
                else if (Auction.CANCELLED.equals(a.state) || Auction.ENDED.equals(a.state) && !Auction.WIN.equals(a.outcome)) leases.unlockAuction(a);
            } catch (RuntimeException failure) { plugin.texts().warn("log.auctionFailed", a.id, root(failure)); }
        }
    }

    private void sweepInvalid() {
        for (String id : schedule.ids()) {
            Auction a = book.get(id);
            if (a == null || !Auction.RUNNING.equals(a.state) || a.pending != null || busy.contains(id) || stillValid(a)) continue;
            try { abort(id, "INVALID"); } catch (RuntimeException failure) { plugin.texts().warn("log.auctionFailed", id, root(failure)); }
        }
    }

    private void resume(String id) {
        if (!busy.add(id)) return;
        CompletableFuture<?> step;
        try { step = step(id); }
        catch (RuntimeException failure) {
            busy.remove(id);
            plugin.texts().warn("log.auctionFailed", id, root(failure));
            return;
        }
        step.whenComplete((ignored, fault) -> {
            busy.remove(id);
            if (fault != null) plugin.texts().warn("log.auctionFailed", id, root(fault));
        });
    }

    private CompletableFuture<?> step(String id) {
        Auction a = book.get(id);
        if (a == null) { work.remove(id); return CompletableFuture.completedFuture(null); }
        // a bid that is half paid comes first; nothing else happens to the auction meanwhile
        if (a.pending != null) return cooling(id + ":bid") ? CompletableFuture.completedFuture(null) : runBid(id);
        List<CompletableFuture<?>> parts = new ArrayList<>();
        if (!a.refunds.isEmpty() && !cooling(id + ":refund")) parts.add(refund(a));
        if (Auction.RUNNING.equals(a.state)) {
            if (System.currentTimeMillis() < a.endAt) schedule.set(id, a.endAt);
            else a = update(id, x -> { if (Auction.RUNNING.equals(x.state) && x.pending == null) x.to(Auction.SETTLING); });
        }
        if (Auction.SETTLING.equals(a.state) && !cooling(id + ":settle")) parts.add(settle(a));
        else if (!Auction.SETTLING.equals(a.state) && a.refunds.isEmpty() && a.pending == null) work.remove(id);
        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0]));
    }

    /** Gives back the first bid on the list; the entry is removed only when the economy says it is done */
    private CompletableFuture<Void> refund(Auction a) {
        Auction.Refund owed = a.refunds.get(0);
        UUID to = UUID.fromString(owed.to);
        return money(ledger -> ledger.refund(to, null, currency(), owed.amount, owed.op)).thenAccept(status -> {
            if (status.equals("OK")) {
                update(a.id, x -> x.refunds.removeIf(r -> r.op.equals(owed.op)));
                tell(owed.to, "auction.refunded", a.claimName, LeaseInput.money(owed.amount));
            } else {
                cooling.put(a.id + ":refund", System.currentTimeMillis());
                plugin.texts().warn("log.auctionRefund", a.id, status);
            }
        });
    }

    /** The end: nobody bid (the claim is let go), the claim is not the lessor's any more (every bid goes back), or the leading bid is paid to the lessor and the claim is leased to its bidder */
    private CompletableFuture<Void> settle(Auction found) {
        String id = found.id;
        Auction a = found;
        if (a.outcome == null) {
            boolean valid = a.bids > 0 && stillValid(a);
            long at = System.currentTimeMillis();
            String result = a.bids == 0 ? Auction.NOBID : valid ? Auction.WIN : Auction.VOID;
            a = update(id, x -> {
                x.outcome = result;
                x.payee = Auction.WIN.equals(result) ? x.lessor : null;
                x.leaseAt = at;
            });
        }
        final Auction now = a;
        switch (now.outcome) {
            case Auction.NOBID -> {
                finish(id, null);
                leases.unlockAuction(now);
                tell(now.lessor, "auction.noBids", now.claimName);
                return CompletableFuture.completedFuture(null);
            }
            case Auction.VOID -> {
                finish(id, Auction::refundLeader);
                leases.unlockAuction(now);
                tell(now.lessor, "auction.invalidLessor", now.claimName);
                tell(now.leader, "auction.invalidBidder", now.claimName);
                return CompletableFuture.completedFuture(null);
            }
            default -> {
                if (now.paid) return lease(now);
                UUID payee = UUID.fromString(now.payee);
                return money(ledger -> ledger.credit(payee, null, currency(), now.price, now.payOperation())).thenCompose(status -> {
                    if (!status.equals("OK")) {
                        cooling.put(id + ":settle", System.currentTimeMillis());
                        plugin.texts().warn("log.auctionPay", id, status);
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return lease(update(id, x -> x.paid = true));
                });
            }
        }
    }

    /** The winning bid is paid; the lease starts for the winner. */
    private CompletableFuture<Void> lease(Auction a) {
        return leases.startWon(a).thenAccept(started -> {
            finish(a.id, null);
            if (!started) { plugin.texts().warn("log.auctionNoClaim", a.id); return; }
            plugin.texts().info("log.auctionWon", a.claimName, a.leaderName, a.price);
            tell(a.leader, "auction.won", a.claimName, LeaseInput.money(a.price));
            tell(a.lessor, "auction.sold", a.claimName, a.leaderName, LeaseInput.money(a.price));
        });
    }

    private void finish(String id, Consumer<Auction> extra) {
        update(id, x -> {
            if (Auction.SETTLING.equals(x.state)) {
                if (extra != null) extra.accept(x);
                x.to(Auction.ENDED);
                x.finishedAt = System.currentTimeMillis();
            }
        });
    }

    // ---- reading ----

    /** Everything about one auction for a window or the chat: identity, names and whose ID each is, price, time, terms and the last bids */
    public String describe(CommandSender viewer, Auction a) {
        String language = plugin.texts().language(viewer);
        var claim = leases.claim(UUID.fromString(a.claimId)).orElse(null);
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        lines.add(text(viewer, "auction.i.id", a.id));
        lines.add(text(viewer, "auction.i.name", claim == null ? a.claimName : claim.displayName(language), a.claimId));
        if (claim != null && !claim.localizedNames().isEmpty())
            lines.add(text(viewer, "auction.i.names", String.join(", ", new java.util.TreeMap<>(claim.localizedNames()).entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).toList())));
        lines.add(text(viewer, "auction.i.lessor", plugin.management().playerName(UUID.fromString(a.lessor)), a.lessor));
        lines.add(text(viewer, "auction.i.state", text(viewer, "auction.state." + a.state)));
        lines.add(text(viewer, "auction.i.price", LeaseInput.money(a.bids == 0 ? a.startPrice : a.price), a.bids == 0 ? text(viewer, "auction.i.startPrice") : ""));
        lines.add(text(viewer, "auction.i.leader", a.leader == null ? "-" : a.leaderName, a.leader == null ? "-" : a.leader));
        if (Auction.RUNNING.equals(a.state)) {
            lines.add(text(viewer, "auction.i.next", a.canBid() ? LeaseInput.money(a.minimumBid()) : "-", LeaseInput.money(a.minRaise)));
            lines.add(text(viewer, "auction.i.left", AuctionRules.clock(a.endAt - now)));
        }
        Lease t = a.terms;
        lines.add(text(viewer, "auction.i.terms", t.perpetual() ? text(viewer, "lease.noTerm") : t.periodDays + " " + text(viewer, "lease.days"), t.maxPeriods, t.graceDays,
                text(viewer, t.autoRenew ? "auction.renewOn" : "auction.renewOff")));
        int from = Math.max(0, a.history.size() - 5);
        for (int i = a.history.size() - 1; i >= from; i--) {
            Auction.Bid bid = a.history.get(i);
            lines.add(text(viewer, "auction.i.bid", bid.number, bid.name, LeaseInput.money(bid.amount)));
        }
        return String.join("\n", lines);
    }

    // ---- the command ----

    /** {@code /land auction ...}; the first word of args is already known to be auction */
    public boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !sender.hasPermission("tokaland.use")) { sender.sendMessage(text(sender, "command.denied")); return true; }
        try {
            if (args.length == 1) { plugin.window().auctionMenu(player); return true; }
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "start" -> {
                    if (args.length < 4 || args.length > 5) player.sendMessage(text(player, "auction.usage"));
                    else start(player, args[2], Long.parseLong(args[3]), args.length == 5 ? Integer.valueOf(args[4]) : null, null, null);
                }
                case "bid" -> { if (args.length != 4) player.sendMessage(text(player, "auction.usage")); else bid(player, args[2], Long.parseLong(args[3])); }
                case "cancel" -> { if (args.length != 3) player.sendMessage(text(player, "auction.usage")); else cancel(player, args[2]); }
                case "list" -> list(player, args.length == 3 ? Integer.parseInt(args[2]) : 1);
                case "info" -> {
                    Auction a = args.length == 3 ? find(args[2]) : null;
                    if (args.length != 3) player.sendMessage(text(player, "auction.usage"));
                    else if (a == null) player.sendMessage(text(player, "auction.notFound"));
                    else player.sendMessage(describe(player, a));
                }
                default -> player.sendMessage(text(player, "auction.usage"));
            }
        } catch (NumberFormatException invalid) { player.sendMessage(text(player, "auction.badNumber", LeaseInput.money(Auction.MAX_PRICE))); }
        return true;
    }

    private void list(Player player, int page) {
        List<Auction> all = running();
        int pages = Math.max(1, (all.size() + 9) / 10);
        if (page < 1 || page > pages) { player.sendMessage(text(player, "command.invalidPage")); return; }
        player.sendMessage(text(player, "auction.listHeader", page, pages, all.size()));
        long now = System.currentTimeMillis();
        for (Auction a : all.subList((page - 1) * 10, Math.min(all.size(), page * 10)))
            player.sendMessage(text(player, "auction.row", a.claimName, LeaseInput.money(a.bids == 0 ? a.startPrice : a.price), a.leader == null ? "-" : a.leaderName, AuctionRules.clock(a.endAt - now)));
    }
}
