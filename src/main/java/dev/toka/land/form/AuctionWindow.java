package dev.toka.land.form;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.window.FormWindowCustom;
import dev.toka.land.Main;
import dev.toka.land.form.LandWindow.Row;
import dev.toka.land.lease.Auction;
import dev.toka.land.lease.AuctionRules;
import dev.toka.land.lease.Lease;
import dev.toka.land.lease.LeaseInput;
import dev.toka.land.lease.LeaseTerms;
import dev.toka.land.model.LandClaim;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Windows for auctions of vacant sub-claims; every action ends in the same Auctions methods the /land auction command uses. */
final class AuctionWindow {
    private static final String ICON = "textures/items/gold_nugget";
    private final Main plugin;
    private final LandWindow host;
    private final LeaseWindow lease;

    AuctionWindow(Main plugin, LandWindow host, LeaseWindow lease) {
        this.plugin = plugin;
        this.host = host;
        this.lease = lease;
    }

    private String t(Player player, String key, Object... args) { return host.text(player, key, args); }

    private static long price(Auction a) { return a.bids == 0 ? a.startPrice : a.price; }

    // ---- the list ----

    void list(Player player, int page, String query, Runnable back) {
        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        for (Auction a : plugin.auctions().running()) {
            if (!LeaseInput.matches(query, a.claimName, a.id)) continue;
            rows.add(new Row(t(player, "aw.row", a.claimName, LeaseInput.money(price(a)), AuctionRules.clock(a.endAt - now)), ICON, () -> detail(player, a.id, () -> list(player, page, query, back))));
        }
        List<Row> fixed = List.of(new Row(t(player, "window.search"), "textures/ui/magnifyingGlass", () -> host.searchForm(player, query, q -> list(player, 0, q, back))));
        String info = rows.isEmpty() ? t(player, "aw.none") : query.isEmpty() ? "" : t(player, "window.filter", query);
        host.paged(player, t(player, "aw.title"), info, fixed, rows, page, p -> list(player, p, query, back), back);
    }

    void mine(Player player, int page, Runnable back) {
        List<Row> rows = new ArrayList<>();
        for (Auction a : plugin.auctions().mine(player.getUniqueId()))
            rows.add(new Row(t(player, "aw.mineRow", a.claimName, t(player, "auction.state." + a.state), LeaseInput.money(price(a))), ICON, () -> detail(player, a.id, () -> mine(player, page, back))));
        host.paged(player, t(player, "aw.mine"), rows.isEmpty() ? t(player, "aw.noneMine") : "", List.of(), rows, page, p -> mine(player, p, back), back);
    }

    // ---- one auction ----

    void detail(Player player, String id, Runnable back) {
        Auction a = plugin.auctions().get(id);
        if (a == null) { back.run(); return; }
        UUID me = player.getUniqueId();
        boolean lessor = a.lessor.equals(me.toString());
        var menu = host.menu(player, a.claimName, plugin.auctions().describe(player, a));
        menu.button(t(player, "window.back"), null, back);
        if (a.open(System.currentTimeMillis()) && !lessor && !me.toString().equals(a.leader) && a.canBid()) menu.button(t(player, "aw.bid"), ICON, () -> bidForm(player, id, back));
        if (Auction.RUNNING.equals(a.state) && (lessor || player.hasPermission("tokaland.admin")) && a.bids == 0 && a.pending == null)
            menu.button(t(player, "aw.cancel"), "textures/ui/trash", () -> host.confirm(player, t(player, "aw.cancel"), t(player, "aw.cancelText", a.claimName),
                    () -> plugin.auctions().cancel(player, id)));
        menu.button(t(player, "aw.refresh"), "textures/ui/refresh", () -> detail(player, id, back));
        menu.show();
    }

    void bidForm(Player player, String id, Runnable back) {
        Auction a = plugin.auctions().get(id);
        if (a == null || !a.open(System.currentTimeMillis()) || !a.canBid()) { player.sendMessage(t(player, "auction.closed")); back.run(); return; }
        String least = String.valueOf(a.minimumBid());
        var window = new FormWindowCustom(a.claimName);
        window.addElement(new ElementHeader(t(player, "aw.bid")));
        window.addElement(new ElementInput(t(player, "aw.bidLabel", LeaseInput.money(a.minimumBid())), least, least));
        host.custom(player, window, response -> {
            Long amount = LeaseWindow.parseLong(LeaseWindow.text(response, 1));
            if (amount == null || amount < 1) { player.sendMessage(t(player, "auction.badNumber", LeaseInput.money(Auction.MAX_PRICE))); bidForm(player, id, back); return; }
            plugin.auctions().bid(player, id, amount);
        });
    }

    // ---- the lessor starts one ----

    void startForm(Player player, UUID claimId) {
        LandClaim sub = plugin.getClaim(claimId).orElse(null);
        if (sub == null || sub.parentClaimId() == null) { player.sendMessage(t(player, "lease.notSub")); return; }
        Lease held = plugin.leases().leaseOf(claimId);
        var config = plugin.getConfig();
        List<Integer> days = LeaseInput.dayChoices(config.getBoolean("lease.allow-perpetual", false));
        List<String> dayNames = days.stream().map(d -> d < 0 ? t(player, "lease.noTerm") : d + " " + t(player, "lease.days")).toList();
        int period = held != null ? held.periodDays : config.getInt("lease.period-days", 90);
        var window = new FormWindowCustom(sub.name());
        window.addElement(new ElementHeader(t(player, "aw.start")));
        window.addElement(new ElementInput(t(player, "aw.startPrice"), "100", ""));
        window.addElement(new ElementInput(t(player, "aw.hours", Math.max(1, config.getInt("lease.auction.min-hours", 1)),
                Math.min(AuctionRules.HARD_MAX_HOURS, config.getInt("lease.auction.max-hours", AuctionRules.HARD_MAX_HOURS))), String.valueOf(AuctionRules.DEFAULT_HOURS), String.valueOf(AuctionRules.DEFAULT_HOURS)));
        window.addElement(new ElementDropdown(t(player, "lw.days"), dayNames, Math.max(0, days.indexOf(period))));
        lease.addTerms(player, window, held != null ? held.maxPeriods : config.getInt("lease.max-periods", 12), held == null || held.autoRenew,
                held != null ? held.graceDays : config.getInt("lease.grace-days", 7), String.valueOf(held == null ? 0 : held.discountAfter), String.valueOf(held == null ? 0 : held.discountPercent));
        host.custom(player, window, response -> {
            Long start = LeaseWindow.parseLong(LeaseWindow.text(response, 1)), hours = LeaseWindow.parseLong(LeaseWindow.text(response, 2));
            LeaseTerms terms = lease.readTerms(response, 5);
            int chosen = response.getDropdownResponse(3).getElementID();
            if (start == null || hours == null || terms == null || hours < 1 || hours > AuctionRules.HARD_MAX_HOURS || chosen < 0 || chosen >= days.size() || !AuctionRules.priceOk(start)) {
                player.sendMessage(t(player, "auction.badNumber", LeaseInput.money(Auction.MAX_PRICE)));
                startForm(player, claimId);
                return;
            }
            plugin.auctions().start(player, sub.name(), start, hours.intValue(), days.get(chosen), terms);
        });
    }
}
