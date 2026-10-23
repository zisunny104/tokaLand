package dev.toka.land.lease;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.Plugin;
import dev.toka.land.Main;
import dev.toka.land.model.AccessPolicy;
import dev.toka.land.model.ClaimAccess;
import dev.toka.land.model.ClaimDetails;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.LandClaim;
import dev.toka.land.model.ReservedNames;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Letting sub-claims. The owner of a claim cuts a sub-claim out of it and offers it; a tenant pays for one or more periods and becomes the
 * owner of the sub-claim until the term ends (or they leave); the rent goes to the lessor, by default the owner of the parent claim.
 * Every payment has a stable operation ID, and a rent is written down as PENDING before money moves, so a stop in the middle is finished
 * by {@link #maintain()} without charging twice.
 */
public final class Leases {
    private static final long RETRY_MILLIS = 60_000L;
    private final Main plugin;
    private final LeaseBook book;
    private final AtomicBoolean ticking = new AtomicBoolean();
    private volatile Method transfer, statusOf;
    private volatile Object cause;
    private Plugin economy;

    public Leases(Main plugin, LeaseBook book) {
        this.plugin = plugin;
        this.book = book;
    }

    private String currency() { return plugin.getConfig().getString("lease.currency", "money"); }

    private String text(CommandSender who, String key, Object... args) { return plugin.texts().text(who, key, args); }

    private void tell(UUID player, String key, Object... args) {
        Player online = plugin.getServer().getPlayer(player).orElse(null);
        if (online != null) plugin.getServer().getScheduler().scheduleTask(plugin, () -> online.sendMessage(text(online, key, args)));
    }

    // ---- money ----

    private boolean connect() {
        Plugin current = plugin.getServer().getPluginManager().getPlugin("tokaEconomy");
        if (current == null || !current.isEnabled()) { transfer = null; economy = null; return false; }
        if (current == economy && transfer != null) return true;
        try {
            ClassLoader loader = current.getClass().getClassLoader();
            Class<?> api = Class.forName("dev.toka.economy.Economy", true, loader), why = Class.forName("dev.toka.economy.Cause", true, loader);
            statusOf = Class.forName("dev.toka.economy.Result", true, loader).getMethod("status");
            cause = why.getMethod("of", String.class, String.class).invoke(null, "land-lease", "tokaLand");
            transfer = api.getMethod("transferOnce", UUID.class, UUID.class, String.class, long.class, why, String.class);
            economy = current;
            return true;
        } catch (ReflectiveOperationException | LinkageError unavailable) { transfer = null; return false; }
    }

    /** Moves the money once for this operation ID; the answer is the economy's status word, OK when it went through (also when it did before) */
    private CompletableFuture<String> pay(UUID from, UUID to, long amount, String operation) {
        if (amount <= 0) return CompletableFuture.completedFuture("OK");
        if (!connect()) return CompletableFuture.completedFuture("UNAVAILABLE");
        try {
            CompletableFuture<?> future = (CompletableFuture<?>) transfer.invoke(null, from, to, currency(), amount, cause, operation);
            return future.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).handle((result, fault) -> {
                if (fault != null || result == null) return "FAILED";
                try { return String.valueOf(statusOf.invoke(result)); } catch (ReflectiveOperationException error) { return "FAILED"; }
            });
        } catch (ReflectiveOperationException | LinkageError error) { return CompletableFuture.completedFuture("UNAVAILABLE"); }
    }

    // ---- claims ----

    private Optional<LandClaim> claim(UUID id) { return plugin.getClaim(id); }

    private Optional<LandClaim> named(String name) {
        return plugin.allClaims().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
    }

    private Optional<UUID> ownerOf(LandClaim claim) { return ClaimAccess.effectiveOwner(plugin.index(), claim.id()); }

    /** The rent goes to whoever owns the upper-level claim now, so a claim that changed hands pays its new owner */
    private UUID lessorOf(Lease lease) {
        return claim(UUID.fromString(lease.claimId)).flatMap(sub -> claim(sub.parentClaimId())).flatMap(this::ownerOf).orElse(UUID.fromString(lease.lessor));
    }

    private CompletableFuture<String> setTenant(LandClaim current, UUID tenant, String tenantName) {
        LandClaim after = tenant == null ? current.withTenancy(null, null, true, true, List.of())
                : current.withTenancy(tenant, tenantName, false, false, List.of());
        return plugin.lands().edit(current, after);
    }

    private void persist(Lease lease) {
        try { book.save(lease); } catch (IOException failure) { throw new CompletionException(failure); }
    }

    // ---- sub-claims ----

    /**
     * Cuts a sub-claim out of the selection in hand. The selection has to lie completely inside one claim, which becomes the parent; inside
     * a chain of claims the innermost is the parent. A selection that sticks out of a claim, or lies in several claims that are not
     * parent and child, cannot be made.
     */
    public void createSub(Player player, String name) {
        var tool = plugin.selectionTool();
        var draft = tool == null ? null : tool.get(player.getUniqueId());
        if (draft == null) { player.sendMessage(text(player, "lease.needSelection")); return; }
        var part = draft.geometry();
        List<LandClaim> containing = new ArrayList<>();
        for (LandClaim each : plugin.allClaims()) {
            boolean inside = each.selections().stream().anyMatch(p -> p.contains(part));
            boolean touches = each.selections().stream().anyMatch(p -> dev.toka.land.LandService.overlaps(p, part));
            if (inside) containing.add(each);
            else if (touches) { player.sendMessage(text(player, "lease.exceeds", each.name())); return; }
        }
        if (containing.isEmpty()) { player.sendMessage(text(player, "lease.noParent")); return; }
        LandClaim parent = null;
        for (LandClaim candidate : containing) {
            boolean innermost = containing.stream().allMatch(other -> other.id().equals(candidate.id()) || isAncestor(other.id(), candidate));
            if (innermost) parent = candidate;
        }
        if (parent == null) { player.sendMessage(text(player, "lease.manyParents")); return; }
        if (!ownerOf(parent).filter(player.getUniqueId()::equals).isPresent() && !player.hasPermission("tokaland.admin")) { player.sendMessage(text(player, "lease.notOwner")); return; }
        if (ReservedNames.is(name)) { player.sendMessage(text(player, "lease.badName")); return; }
        final LandClaim chosen = parent;
        ClaimDetails.TeleportPoint point = draft.teleport();
        if (point != null && !part.contains(point.world(), (int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()))) point = null;
        var details = new ClaimDetails(player.getUniqueId(), player.getName(), point, false, 0L, null);
        var sub = new LandClaim(UUID.randomUUID(), name, ClaimKind.PLAYER, "sub", null, chosen.id(), true, true, List.of(part), List.of(),
                AccessPolicy.privateDefault(), Map.of(), null, null, details);
        plugin.lands().create(sub).whenComplete((revision, error) -> plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            if (error != null) player.sendMessage(text(player, "lease.failed", rootMessage(error)));
            else { tool.forget(player.getUniqueId(), draft); player.sendMessage(text(player, "lease.subCreated", name, chosen.name())); }
        }));
    }

    private boolean isAncestor(UUID ancestor, LandClaim of) {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (UUID parent = of.parentClaimId(); parent != null && seen.add(parent); parent = claim(parent).map(LandClaim::parentClaimId).orElse(null))
            if (parent.equals(ancestor)) return true;
        return false;
    }

    // ---- offering, renting, leaving ----

    public void offer(Player player, String subName, long rent, Integer days) {
        var sub = named(subName).orElse(null);
        if (sub == null) { player.sendMessage(text(player, "lease.noClaim", subName)); return; }
        if (sub.parentClaimId() == null) { player.sendMessage(text(player, "lease.notSub")); return; }
        var parent = claim(sub.parentClaimId()).orElse(null);
        UUID lessor = parent == null ? null : ownerOf(parent).orElse(null);
        Lease existing = book.get(sub.id());
        if (lessor == null || (!lessor.equals(player.getUniqueId()) && !player.hasPermission("tokaland.admin"))) { player.sendMessage(text(player, "lease.notOwner")); return; }
        if (existing != null && !Lease.VACANT.equals(existing.state)) { player.sendMessage(text(player, "lease.busy")); return; }
        if (rent < 1) { player.sendMessage(text(player, "lease.badNumber")); return; }
        int period = days == null ? plugin.getConfig().getInt("lease.period-days", 90) : days;
        if (period == 0 || period < -1 || period > 3650 || (period == -1 && !plugin.getConfig().getBoolean("lease.allow-perpetual", false))) {
            player.sendMessage(text(player, period == -1 ? "lease.perpetualOff" : "lease.badNumber"));
            return;
        }
        Lease lease = existing != null ? existing : new Lease();
        lease.claimId = sub.id().toString();
        lease.lessor = lessor.toString();
        lease.rent = rent;
        lease.periodDays = period;
        if (existing == null) {
            lease.maxPeriods = plugin.getConfig().getInt("lease.max-periods", 12);
            lease.graceDays = plugin.getConfig().getInt("lease.grace-days", 7);
        }
        lease.state = Lease.VACANT;
        try { persist(lease); } catch (RuntimeException failure) { player.sendMessage(text(player, "lease.failed", rootMessage(failure))); return; }
        player.sendMessage(text(player, "lease.offered", sub.name(), rent, period == -1 ? text(player, "lease.noTerm") : period + " " + text(player, "lease.days")));
    }

    /** Changes one term of an offer: max (periods), grace (days), renew (on|off), discount (after:percent), rent */
    public void set(Player player, String subName, String key, String value) {
        var sub = named(subName).orElse(null);
        Lease lease = sub == null ? null : book.get(sub.id());
        if (lease == null) { player.sendMessage(text(player, "lease.noLease")); return; }
        if (!lease.lessor.equals(player.getUniqueId().toString()) && !player.hasPermission("tokaland.admin")) { player.sendMessage(text(player, "lease.notOwner")); return; }
        try {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "max" -> lease.maxPeriods = Math.max(1, Math.min(120, Integer.parseInt(value)));
                case "grace" -> lease.graceDays = Math.max(0, Math.min(90, Integer.parseInt(value)));
                case "renew" -> lease.autoRenew = value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true");
                case "rent" -> {
                    long rent = Long.parseLong(value);
                    if (rent < 1) throw new NumberFormatException();
                    lease.rent = rent;
                }
                case "discount" -> {
                    String[] parts = value.split(":");
                    lease.discountAfter = Math.max(0, Integer.parseInt(parts[0]));
                    lease.discountPercent = Math.max(0, Math.min(90, Integer.parseInt(parts[1])));
                }
                default -> { player.sendMessage(text(player, "lease.usage")); return; }
            }
            persist(lease);
            player.sendMessage(text(player, "lease.changed", sub.name()));
        } catch (RuntimeException invalid) { player.sendMessage(text(player, "lease.badNumber")); }
    }

    public void withdrawOffer(Player player, String subName) {
        var sub = named(subName).orElse(null);
        Lease lease = sub == null ? null : book.get(sub.id());
        if (lease == null) { player.sendMessage(text(player, "lease.noLease")); return; }
        if (!lease.lessor.equals(player.getUniqueId().toString()) && !player.hasPermission("tokaland.admin")) { player.sendMessage(text(player, "lease.notOwner")); return; }
        if (!Lease.VACANT.equals(lease.state)) { player.sendMessage(text(player, "lease.busy")); return; }
        try { book.delete(sub.id()); player.sendMessage(text(player, "lease.withdrawn", sub.name())); }
        catch (IOException failure) { player.sendMessage(text(player, "lease.failed", rootMessage(failure))); }
    }

    /** {@code start} and {@code endCap} are optional dates; with no start the term begins now */
    public void rent(Player player, String subName, int periods, LocalDate start, LocalDate endCap) {
        var sub = named(subName).orElse(null);
        Lease lease = sub == null ? null : book.get(sub.id());
        if (lease == null || !Lease.VACANT.equals(lease.state)) { player.sendMessage(text(player, "lease.notVacant")); return; }
        UUID lessor = lessorOf(lease);
        if (lessor.equals(player.getUniqueId())) { player.sendMessage(text(player, "lease.ownLand")); return; }
        if (lease.perpetual()) periods = 1;
        if (periods < 1 || periods > lease.maxPeriods) { player.sendMessage(text(player, "lease.periods", lease.maxPeriods)); return; }
        ZoneId zone = ZoneId.of(plugin.getConfig().getString("lease.timezone", "Asia/Taipei"));
        long now = System.currentTimeMillis();
        long startAt = start == null ? now : Math.max(now, start.atStartOfDay(zone).toInstant().toEpochMilli());
        long cap = endCap == null ? 0 : endCap.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        if (!lease.perpetual() && cap != 0 && startAt + periods * lease.periodMillis() > cap) { player.sendMessage(text(player, "lease.pastEnd")); return; }
        long amount;
        try { amount = lease.total(periods); } catch (ArithmeticException tooBig) { player.sendMessage(text(player, "lease.badNumber")); return; }
        lease.state = Lease.PENDING;
        lease.tenant = player.getUniqueId().toString();
        lease.tenantName = player.getName();
        lease.startAt = startAt;
        lease.endCap = cap;
        lease.pendingPeriods = periods;
        lease.pendingAmount = amount;
        lease.operation = "lease:" + sub.id() + ":" + now;
        lease.paidUntil = 0;
        lease.lastTry = now;
        try { persist(lease); } catch (RuntimeException failure) { player.sendMessage(text(player, "lease.failed", rootMessage(failure))); return; }
        player.sendMessage(text(player, "lease.paying", amount));
        UUID tenant = player.getUniqueId();
        complete(lease, true).whenComplete((status, error) -> {
            if (error != null) tell(tenant, "lease.failed", rootMessage(error));
            else if (status.equals("OK")) tell(tenant, lease.state.equals(Lease.RESERVED) ? "lease.reserved" : "lease.rented", sub.name(), formatTime(lease.shownEnd()));
            else if (status.equals("INSUFFICIENT_FUNDS")) tell(tenant, "lease.insufficient", amount);
            else tell(tenant, "lease.failed", status);
        });
    }

    /** Finishes a PENDING rent: the payment (once), then the sub-claim goes to the tenant, now or at the start date. A payment refused for good puts the offer back. */
    private CompletableFuture<String> complete(Lease lease, boolean firstTry) {
        UUID tenant = UUID.fromString(lease.tenant), lessor = lessorOf(lease);
        return pay(tenant, lessor, lease.pendingAmount, lease.operation).thenCompose(status -> {
            if (!status.equals("OK")) {
                // refused for good, or (on the first try) never sent because the economy is down: nothing moved, the offer is open again.
                // After a stop it stays pending: the money may have moved, and the same operation ID settles it.
                boolean nothingMoved = !status.equals("FAILED") && (firstTry || !status.equals("UNAVAILABLE"));
                if (nothingMoved) {
                    lease.state = Lease.VACANT;
                    lease.tenant = null;
                    lease.tenantName = null;
                    lease.operation = null;
                    persist(lease);
                }
                return CompletableFuture.completedFuture(status);
            }
            lease.paidUntil = lease.perpetual() ? 0 : lease.startAt + lease.pendingPeriods * lease.periodMillis();
            if (lease.startAt > System.currentTimeMillis()) {
                lease.state = Lease.RESERVED;
                persist(lease);
                return CompletableFuture.completedFuture("OK");
            }
            return start(lease).thenApply(ok -> "OK");
        });
    }

    private CompletableFuture<Void> start(Lease lease) {
        var current = claim(UUID.fromString(lease.claimId)).orElse(null);
        if (current == null) {
            lease.state = Lease.VACANT;
            lease.tenant = null;
            persist(lease);
            return CompletableFuture.completedFuture(null);
        }
        return setTenant(current, UUID.fromString(lease.tenant), lease.tenantName).thenAccept(revision -> {
            lease.state = Lease.LEASED;
            persist(lease);
        });
    }

    /** The tenant or the lessor ends the letting; what was paid for the rest of the term is not given back */
    public void release(Player player, String subName) {
        var sub = named(subName).orElse(null);
        Lease lease = sub == null ? null : book.get(sub.id());
        if (lease == null || lease.tenant == null) { player.sendMessage(text(player, "lease.noLease")); return; }
        boolean tenant = lease.tenant.equals(player.getUniqueId().toString());
        if (!tenant && !lease.lessor.equals(player.getUniqueId().toString()) && !player.hasPermission("tokaland.admin")) { player.sendMessage(text(player, "lease.notOwner")); return; }
        CompletableFuture<String> refund = lease.state.equals(Lease.RESERVED) && System.currentTimeMillis() < lease.startAt
                ? pay(lessorOf(lease), UUID.fromString(lease.tenant), lease.pendingAmount, lease.operation + ":refund") : CompletableFuture.completedFuture("OK");
        refund.thenCompose(status -> status.equals("OK") ? end(lease, tenant) : CompletableFuture.<Void>failedFuture(new IllegalStateException(status))).whenComplete((ok, error) -> plugin.getServer().getScheduler().scheduleTask(plugin,
                () -> player.sendMessage(error == null ? text(player, "lease.released", sub.name()) : text(player, "lease.failed", rootMessage(error)))));
    }

    private CompletableFuture<Void> end(Lease lease, boolean tenantLeft) {
        UUID tenant = lease.tenant == null ? null : UUID.fromString(lease.tenant);
        var current = claim(UUID.fromString(lease.claimId)).orElse(null);
        CompletableFuture<?> flip = current == null || lease.state.equals(Lease.RESERVED) ? CompletableFuture.completedFuture(null) : setTenant(current, null, null);
        return flip.thenAccept(done -> {
            lease.state = Lease.VACANT;
            lease.tenant = null;
            lease.tenantName = null;
            lease.operation = null;
            lease.paidUntil = 0;
            lease.graceUntil = 0;
            persist(lease);
            if (tenant != null && !tenantLeft) tell(tenant, "lease.ended", current == null ? "" : current.name());
        });
    }

    // ---- reading ----

    public void info(Player player, String subName) {
        var sub = named(subName).orElse(null);
        Lease lease = sub == null ? null : book.get(sub.id());
        if (lease == null) { player.sendMessage(text(player, "lease.noLease")); return; }
        player.sendMessage(text(player, "lease.info", sub.name(), lease.state, lease.rent,
                lease.perpetual() ? text(player, "lease.noTerm") : lease.periodDays + " " + text(player, "lease.days"),
                lease.maxPeriods, lease.discountAfter > 0 ? lease.discountAfter + ":" + lease.discountPercent + "%" : "-",
                lease.tenantName == null ? "-" : lease.tenantName, lease.paidUntil == 0 ? "-" : formatTime(lease.shownEnd())));
    }

    public void list(Player player) {
        List<String> rows = new ArrayList<>();
        for (Lease lease : book.all()) {
            var sub = claim(UUID.fromString(lease.claimId)).orElse(null);
            if (sub == null) continue;
            boolean mine = lease.lessor.equals(player.getUniqueId().toString()) || player.getUniqueId().toString().equals(lease.tenant);
            if (Lease.VACANT.equals(lease.state) || mine)
                rows.add(text(player, "lease.row", sub.name(), lease.state, lease.rent, lease.perpetual() ? text(player, "lease.noTerm") : lease.periodDays + " " + text(player, "lease.days")));
        }
        player.sendMessage(text(player, "lease.listHeader", rows.size()));
        rows.stream().limit(30).forEach(player::sendMessage);
    }

    // ---- upkeep ----

    /** Called often: finishes interrupted rents, starts reserved terms, renews, and ends what has run out */
    public void maintain() {
        if (!ticking.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        List<CompletableFuture<?>> work = new ArrayList<>();
        for (Lease lease : List.copyOf(book.all())) {
            try {
                // a sub-claim that was deleted takes its letting with it
                if (claim(UUID.fromString(lease.claimId)).isEmpty()) {
                    book.delete(UUID.fromString(lease.claimId));
                    plugin.getLogger().info("下級領地已不存在，移除它的租賃資料：" + lease.claimId);
                    continue;
                }
                switch (lease.state) {
                    case Lease.PENDING -> {
                        if (now - lease.lastTry >= RETRY_MILLIS) { lease.lastTry = now; work.add(complete(lease, false)); }
                    }
                    case Lease.RESERVED -> { if (now >= lease.startAt) work.add(start(lease)); }
                    case Lease.LEASED -> { if (!lease.perpetual() && now >= lease.paidUntil) work.add(renew(lease, now)); }
                    case Lease.GRACE -> {
                        if (now > lease.graceUntil) work.add(end(lease, false));
                        else if (now - lease.lastTry >= RETRY_MILLIS) { lease.lastTry = now; work.add(renew(lease, now)); }
                    }
                    default -> { }
                }
            } catch (RuntimeException | IOException failure) { plugin.getLogger().warning("租賃處理失敗：" + lease.claimId + " " + failure.getClass().getSimpleName()); }
        }
        CompletableFuture.allOf(work.toArray(new CompletableFuture[0])).whenComplete((ignored, failure) -> {
            if (failure != null) plugin.getLogger().warning("租賃處理未完成：" + rootMessage(failure));
            ticking.set(false);
        });
    }

    /** At the end of what is paid: another period if the lease renews and the cap allows, else the term is over; money short means grace */
    private CompletableFuture<Void> renew(Lease lease, long now) {
        boolean capped = lease.endCap != 0 && lease.paidUntil + lease.periodMillis() > lease.endCap;
        if (!lease.autoRenew || capped) return now < lease.paidUntil + Lease.DAY ? CompletableFuture.completedFuture(null) : end(lease, false);
        UUID tenant = UUID.fromString(lease.tenant), lessor = lessorOf(lease);
        return pay(tenant, lessor, lease.rent, "lease:" + lease.claimId + ":renew:" + lease.paidUntil).thenAccept(status -> {
            if (status.equals("OK")) {
                lease.paidUntil += lease.periodMillis();
                lease.state = Lease.LEASED;
                lease.graceUntil = 0;
                persist(lease);
            } else if (!lease.state.equals(Lease.GRACE) && !status.equals("UNAVAILABLE")) {
                lease.state = Lease.GRACE;
                lease.graceUntil = lease.paidUntil + lease.graceDays * Lease.DAY;
                lease.lastTry = now;
                persist(lease);
                tell(tenant, "lease.grace", claim(UUID.fromString(lease.claimId)).map(LandClaim::name).orElse(""), lease.rent, lease.graceDays);
            }
        });
    }

    private String formatTime(long millis) {
        return millis == 0 ? "-" : Instant.ofEpochMilli(millis).atZone(ZoneId.of(plugin.getConfig().getString("lease.timezone", "Asia/Taipei"))).toLocalDate().toString();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    // ---- the command ----

    /** {@code /land sub <claim> <name>} and {@code /land lease ...}; the first word of args is already known to be sub or lease */
    public boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !sender.hasPermission("tokaland.use")) { sender.sendMessage(text(sender, "command.denied")); return true; }
        try {
            if (args[0].equalsIgnoreCase("sub")) {
                if (args.length != 2) player.sendMessage(text(player, "lease.usage")); else createSub(player, args[1]);
                return true;
            }
            if (args.length < 2) { player.sendMessage(text(player, "lease.usage")); return true; }
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "offer" -> {
                    if (args.length < 4) player.sendMessage(text(player, "lease.usage"));
                    else offer(player, args[2], Long.parseLong(args[3]), args.length > 4 ? Integer.valueOf(args[4]) : null);
                }
                case "set" -> { if (args.length != 5) player.sendMessage(text(player, "lease.usage")); else set(player, args[2], args[3], args[4]); }
                case "cancel" -> { if (args.length != 3) player.sendMessage(text(player, "lease.usage")); else withdrawOffer(player, args[2]); }
                case "rent" -> {
                    if (args.length < 3) { player.sendMessage(text(player, "lease.usage")); break; }
                    int periods = 1;
                    LocalDate start = null, end = null;
                    for (int i = 3; i < args.length; i++) {
                        if (args[i].equalsIgnoreCase("start") && i + 1 < args.length) start = LocalDate.parse(args[++i]);
                        else if (args[i].equalsIgnoreCase("end") && i + 1 < args.length) end = LocalDate.parse(args[++i]);
                        else periods = Integer.parseInt(args[i]);
                    }
                    rent(player, args[2], periods, start, end);
                }
                case "release", "leave" -> { if (args.length != 3) player.sendMessage(text(player, "lease.usage")); else release(player, args[2]); }
                case "info" -> { if (args.length != 3) player.sendMessage(text(player, "lease.usage")); else info(player, args[2]); }
                case "list" -> list(player);
                default -> player.sendMessage(text(player, "lease.usage"));
            }
        } catch (NumberFormatException | java.time.format.DateTimeParseException invalid) { player.sendMessage(text(player, "lease.badNumber")); }
        return true;
    }
}
