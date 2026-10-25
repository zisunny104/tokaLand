package dev.toka.land.form;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementDivider;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindowCustom;
import dev.toka.land.Main;
import dev.toka.land.form.LandWindow.Row;
import dev.toka.land.lease.Lease;
import dev.toka.land.lease.LeaseInput;
import dev.toka.land.lease.LeaseTerms;
import dev.toka.land.model.LandClaim;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Windows for letting sub-claims; every action ends in the same Leases methods the /land lease command uses. */
final class LeaseWindow {
    private static final String COIN = "textures/items/gold_ingot";
    private final Main plugin;
    private final LandWindow host;

    LeaseWindow(Main plugin, LandWindow host) {
        this.plugin = plugin;
        this.host = host;
    }

    private String t(Player player, String key, Object... args) { return host.text(player, key, args); }

    String zone() { return plugin.getConfig().getString("lease.timezone", "Asia/Taipei"); }

    private String date(long millis) {
        return millis == 0 ? "-" : Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone())).toLocalDate().toString();
    }

    private String period(Player player, Lease lease) { return lease.perpetual() ? t(player, "lease.noTerm") : lease.periodDays + " " + t(player, "lease.days"); }

    private String claimName(Lease lease) { return plugin.getClaim(UUID.fromString(lease.claimId)).map(LandClaim::name).orElse(lease.claimId); }

    static String text(FormResponseCustom response, int index) {
        String value = response.getInputResponse(index);
        return value == null ? "" : value.strip();
    }

    // ---- menu ----

    void menu(Player player) {
        host.menu(player, t(player, "lw.title"), "")
                .button(t(player, "window.back"), null, () -> host.open(player))
                .button(t(player, "lw.openLettings"), "textures/items/emerald", () -> lettings(player, 0, ""))
                .button(t(player, "lw.myRentals"), COIN, () -> myRentals(player, 0))
                .button(t(player, "aw.title"), "textures/items/gold_nugget", () -> host.auctionMenu(player))
                .button(t(player, "aw.mine"), "textures/items/book_enchanted", () -> host.auctionMine(player))
                .button(t(player, "lw.myOffers"), "textures/items/book_writable", () -> myOffers(player, 0))
                .button(t(player, "lw.offer"), "textures/items/name_tag", () -> offerForm(player, null))
                .button(t(player, "window.createSub"), "textures/items/map_filled", () -> subForm(player))
                .show();
    }

    // ---- sub-claim creation ----

    /** The parent is found from the selection, exactly like /land sub. */
    void subForm(Player player) {
        var tool = plugin.selectionTool();
        boolean selected = tool != null && tool.get(player.getUniqueId()) != null;
        var window = new FormWindowCustom(t(player, "window.createSub"));
        window.addElement(new ElementHeader(t(player, "window.createSub")));
        window.addElement(new ElementLabel(t(player, selected ? "lw.selectionYes" : "lw.selectionNo")));
        window.addElement(new ElementInput(t(player, "lw.subName"), "", ""));
        host.custom(player, window, response -> {
            String name = text(response, 2);
            if (!LeaseInput.commandSafe(name)) { player.sendMessage(t(player, "window.badName")); subForm(player); return; }
            plugin.leases().createSub(player, name);
        });
    }

    // ---- lessor: offer ----

    void offerForm(Player player, UUID preselected) {
        List<LandClaim> subs = plugin.index().ownedBy(player.getUniqueId()).stream().filter(claim -> claim.parentClaimId() != null)
                .filter(claim -> { Lease held = plugin.leases().leaseOf(claim.id()); return held == null || Lease.VACANT.equals(held.state); })
                .sorted(Comparator.comparing(LandClaim::name, String.CASE_INSENSITIVE_ORDER)).toList();
        if (subs.isEmpty()) { player.sendMessage(t(player, "lw.noSubs")); return; }
        int chosen = 0;
        for (int i = 0; i < subs.size(); i++) if (subs.get(i).id().equals(preselected)) chosen = i;
        var config = plugin.getConfig();
        List<Integer> days = LeaseInput.dayChoices(config.getBoolean("lease.allow-perpetual", false));
        List<String> dayNames = days.stream().map(d -> d < 0 ? t(player, "lease.noTerm") : d + " " + t(player, "lease.days")).toList();
        int defaultDays = Math.max(0, days.indexOf(config.getInt("lease.period-days", 90)));
        var window = new FormWindowCustom(t(player, "lw.offer"));
        window.addElement(new ElementHeader(t(player, "lw.offer")));
        window.addElement(new ElementDropdown(t(player, "lw.sub"), subs.stream().map(LandClaim::name).toList(), chosen));
        window.addElement(new ElementInput(t(player, "lw.rent"), "100", ""));
        window.addElement(new ElementDropdown(t(player, "lw.days"), dayNames, defaultDays));
        addTerms(player, window, config.getInt("lease.max-periods", 12), true, config.getInt("lease.grace-days", 7), "0", "0");
        host.custom(player, window, response -> {
            LandClaim sub = subs.get(Math.min(response.getDropdownResponse(1).getElementID(), subs.size() - 1));
            Long rent = parseLong(text(response, 2));
            LeaseTerms terms = readTerms(response, 5);
            if (rent == null || rent < 1 || terms == null) { player.sendMessage(t(player, "lease.badNumber")); offerForm(player, sub.id()); return; }
            plugin.leases().offer(player, sub.name(), rent, days.get(response.getDropdownResponse(3).getElementID()), terms);
        });
    }

    /** Elements from {@code first} on: max periods slider, renew toggle, grace slider, discount after, discount percent. */
    void addTerms(Player player, FormWindowCustom window, int max, boolean renew, int grace, String after, String percent) {
        window.addElement(new ElementDivider());
        window.addElement(new ElementSlider(t(player, "lw.maxPeriods"), 1, LeaseTerms.MAX_PERIODS, 1, LeaseTerms.clampPeriods(max)));
        window.addElement(new ElementToggle(t(player, "lw.autoRenew"), renew));
        window.addElement(new ElementSlider(t(player, "lw.grace"), 0, LeaseTerms.MAX_GRACE, 1, LeaseTerms.clampGrace(grace)));
        window.addElement(new ElementInput(t(player, "lw.discountAfter"), "0", after));
        window.addElement(new ElementInput(t(player, "lw.discountPercent"), "0", percent));
    }

    LeaseTerms readTerms(FormResponseCustom response, int first) {
        Long after = parseLong(text(response, first + 3)), percent = parseLong(text(response, first + 4));
        if (after == null || percent == null || after < 0 || percent < 0 || after > 1000 || percent > 100) return null;
        return new LeaseTerms((int) response.getSliderResponse(first), (int) response.getSliderResponse(first + 2), response.getToggleResponse(first + 1),
                after.intValue(), percent.intValue());
    }

    static Long parseLong(String text) {
        try { return Long.parseLong(text); } catch (NumberFormatException invalid) { return null; }
    }

    // ---- lessor: manage ----

    private void myOffers(Player player, int page) {
        List<Row> rows = new ArrayList<>();
        plugin.leases().allLeases().stream().filter(lease -> player.getUniqueId().toString().equals(lease.lessor))
                .filter(lease -> plugin.getClaim(UUID.fromString(lease.claimId)).isPresent())
                .sorted(Comparator.comparing(this::claimName, String.CASE_INSENSITIVE_ORDER))
                .forEach(lease -> rows.add(new Row(claimName(lease) + " §8(" + host.stateText(player, lease) + ")", COIN, () -> manage(player, lease.claimId))));
        host.paged(player, t(player, "lw.myOffers"), rows.isEmpty() ? t(player, "lw.noOffers") : "", List.of(), rows, page, p -> myOffers(player, p), () -> menu(player));
    }

    private void manage(Player player, String claimId) {
        Lease lease = plugin.leases().leaseOf(UUID.fromString(claimId));
        if (lease == null) { myOffers(player, 0); return; }
        var menu = host.menu(player, claimName(lease), t(player, "lw.state", host.stateText(player, lease), LeaseInput.money(lease.rent), period(player, lease),
                lease.tenantName == null ? "-" : lease.tenantName, lease.paidUntil == 0 ? "-" : date(lease.shownEnd())));
        menu.button(t(player, "window.back"), null, () -> myOffers(player, 0));
        menu.button(t(player, "lw.editTerms"), "textures/items/book_writable", () -> editForm(player, claimId));
        if (Lease.VACANT.equals(lease.state))
            menu.button(t(player, "lw.cancelOffer"), "textures/ui/trash", () -> host.confirm(player, t(player, "lw.cancelOffer"),
                    t(player, "lw.cancelText", claimName(lease)), () -> plugin.leases().withdrawOffer(player, claimName(lease))));
        menu.show();
    }

    private void editForm(Player player, String claimId) {
        Lease lease = plugin.leases().leaseOf(UUID.fromString(claimId));
        if (lease == null) { myOffers(player, 0); return; }
        var window = new FormWindowCustom(claimName(lease));
        window.addElement(new ElementHeader(t(player, "lw.editTerms")));
        window.addElement(new ElementInput(t(player, "lw.rent"), "100", String.valueOf(lease.rent)));
        addTerms(player, window, lease.maxPeriods, lease.autoRenew, lease.graceDays, String.valueOf(lease.discountAfter), String.valueOf(lease.discountPercent));
        host.custom(player, window, response -> {
            Long rent = parseLong(text(response, 1));
            LeaseTerms terms = readTerms(response, 3);
            if (rent == null || rent < 1 || terms == null) { player.sendMessage(t(player, "lease.badNumber")); editForm(player, claimId); return; }
            plugin.leases().update(player, claimName(lease), rent, terms);
        });
    }

    // ---- tenant: find and rent ----

    private void lettings(Player player, int page, String query) {
        List<Row> rows = new ArrayList<>();
        plugin.leases().allLeases().stream().filter(lease -> Lease.VACANT.equals(lease.state) && lease.auctionId == null)
                .filter(lease -> plugin.getClaim(UUID.fromString(lease.claimId)).isPresent())
                .filter(lease -> LeaseInput.matches(query, claimName(lease), lease.claimId))
                .sorted(Comparator.comparing(this::claimName, String.CASE_INSENSITIVE_ORDER))
                .forEach(lease -> rows.add(new Row(t(player, "lw.lettingButton", claimName(lease), LeaseInput.money(lease.rent)), COIN, () -> rentForm(player, lease.claimId))));
        List<Row> fixed = List.of(new Row(t(player, "window.search"), "textures/ui/magnifyingGlass", () -> host.searchForm(player, query, q -> lettings(player, 0, q))));
        String info = rows.isEmpty() ? t(player, "lw.noLettings") : query.isEmpty() ? "" : t(player, "window.filter", query);
        host.paged(player, t(player, "lw.openLettings"), info, fixed, rows, page, p -> lettings(player, p, query), () -> menu(player));
    }

    private void rentForm(Player player, String claimId) {
        Lease lease = plugin.leases().leaseOf(UUID.fromString(claimId));
        if (lease == null || !Lease.VACANT.equals(lease.state)) { player.sendMessage(t(player, "lease.notVacant")); lettings(player, 0, ""); return; }
        String name = claimName(lease);
        int max = lease.perpetual() ? 1 : lease.maxPeriods;
        List<String> options = new ArrayList<>();
        for (int periods = 1; periods <= max; periods++) {
            try { options.add(t(player, "lw.periodOption", periods, LeaseInput.money(lease.total(periods)))); }
            catch (ArithmeticException tooBig) { break; }
        }
        var window = new FormWindowCustom(name);
        window.addElement(new ElementHeader(t(player, "lw.openLettings")));
        window.addElement(new ElementLabel(t(player, "lw.rentInfo", LeaseInput.money(lease.rent), period(player, lease), max)));
        window.addElement(new ElementDropdown(t(player, "lw.periods"), options));
        if (!lease.perpetual()) {
            window.addElement(new ElementInput(t(player, "lw.startDate"), "yyyy-MM-dd", ""));
            window.addElement(new ElementInput(t(player, "lw.endDate"), "yyyy-MM-dd", ""));
        }
        host.custom(player, window, response -> {
            int periods = response.getDropdownResponse(2).getElementID() + 1;
            LocalDate start = null, end = null;
            try {
                if (!lease.perpetual()) { start = LeaseInput.parseDate(text(response, 3)); end = LeaseInput.parseDate(text(response, 4)); }
            } catch (DateTimeParseException invalid) { player.sendMessage(t(player, "lw.badDate")); rentForm(player, claimId); return; }
            LocalDate from = start == null ? LeaseInput.today(zone()) : start;
            String until = lease.perpetual() ? t(player, "lease.noTerm") : LeaseInput.endDate(from, periods, lease.periodDays).toString();
            LocalDate chosenStart = start, chosenEnd = end;
            host.confirm(player, name, t(player, "lw.confirmRent", name, LeaseInput.money(lease.total(periods)), until),
                    () -> plugin.leases().rent(player, name, periods, chosenStart, chosenEnd));
        });
    }

    // ---- tenant: my rentals ----

    private void myRentals(Player player, int page) {
        List<Row> rows = new ArrayList<>();
        plugin.leases().allLeases().stream().filter(lease -> player.getUniqueId().toString().equals(lease.tenant))
                .filter(lease -> plugin.getClaim(UUID.fromString(lease.claimId)).isPresent())
                .sorted(Comparator.comparing(this::claimName, String.CASE_INSENSITIVE_ORDER))
                .forEach(lease -> rows.add(new Row(claimName(lease) + " §8(" + host.stateText(player, lease) + ")", COIN, () -> rentalDetail(player, lease))));
        host.paged(player, t(player, "lw.myRentals"), rows.isEmpty() ? t(player, "lw.noRentals") : "", List.of(), rows, page, p -> myRentals(player, p), () -> menu(player));
    }

    void rentalDetail(Player player, Lease lease) {
        String name = claimName(lease);
        host.menu(player, name, t(player, "lw.state", host.stateText(player, lease), LeaseInput.money(lease.rent), period(player, lease),
                        lease.tenantName == null ? "-" : lease.tenantName, lease.paidUntil == 0 ? "-" : date(lease.shownEnd())))
                .button(t(player, "window.back"), null, () -> myRentals(player, 0))
                .button(t(player, "lw.release"), "textures/ui/trash", () -> host.confirm(player, t(player, "lw.release"), t(player, "lw.releaseText", name),
                        () -> plugin.leases().release(player, name)))
                .show();
    }
}
