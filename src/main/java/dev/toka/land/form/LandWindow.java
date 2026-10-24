package dev.toka.land.form;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.response.FormResponse;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import dev.toka.land.Main;
import dev.toka.land.lease.Lease;
import dev.toka.land.lease.LeaseInput;
import dev.toka.land.model.ClaimAccess;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.LandClaim;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The player's claims as windows (list with search, detail with actions) and the host of every window of this plugin: it remembers which
 * window belongs to whom and hands the answer to the handler. Actions go through the /land commands, so rules live in one place.
 */
public final class LandWindow implements Listener {
    static final int PAGE = 8;
    private record Entry(Player player, Consumer<FormResponse> handler) {}
    record Row(String text, String icon, Runnable action) {}
    private final Main plugin;
    private final LeaseWindow lease;
    private final Map<FormWindow, Entry> open = Collections.synchronizedMap(new WeakHashMap<>());

    public LandWindow(Main plugin) {
        this.plugin = plugin;
        this.lease = new LeaseWindow(plugin, this);
    }

    public void open(Player player) { list(player, 0, ""); }

    public void leaseMenu(Player player) { lease.menu(player); }

    String text(Player player, String key, Object... args) { return plugin.texts().text(player, key, args); }

    void show(Player player, FormWindow window, Consumer<FormResponse> handler) {
        open.put(window, new Entry(player, handler));
        player.showFormWindow(window);
    }

    void run(Player player, String command) { plugin.getServer().dispatchCommand(player, command); }

    Menu menu(Player player, String title, String content) { return new Menu(this, player, title, content); }

    /** Custom window; the handler gets the answers. */
    void custom(Player player, FormWindowCustom window, Consumer<FormResponseCustom> handler) {
        show(player, window, response -> { if (response instanceof FormResponseCustom custom) handler.accept(custom); });
    }

    /** Yes/no window; only "yes" runs the action. */
    void confirm(Player player, String title, String content, Runnable yes) {
        show(player, new FormWindowModal(title, content, text(player, "window.yes"), text(player, "window.no")),
                response -> { if (response instanceof FormResponseModal modal && modal.getClickedButtonId() == 0) yes.run(); });
    }

    /** The shared search window: one input; the handler gets the query (blank clears the search). */
    void searchForm(Player player, String current, Consumer<String> apply) {
        var window = new FormWindowCustom(text(player, "window.search"));
        window.addElement(new ElementHeader(text(player, "window.search")));
        window.addElement(new ElementInput(text(player, "window.searchLabel"), text(player, "window.searchHint"), current));
        custom(player, window, response -> apply.accept(response.getInputResponse(1) == null ? "" : response.getInputResponse(1).strip()));
    }

    /**
     * One page of a button list: back first, the fixed buttons, then the rows of the page and the page buttons.
     * {@code back} null means the list is the top of the menu and the first button only closes.
     */
    void paged(Player player, String title, String info, List<Row> fixed, List<Row> rows, int page, IntConsumer reopen, Runnable back) {
        int pages = Math.max(1, (rows.size() + PAGE - 1) / PAGE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        String header = text(player, "window.pageOf", current + 1, pages);
        var menu = menu(player, title, info.isEmpty() ? header : info + "\n" + header);
        menu.button(text(player, back == null ? "window.close" : "window.back"), null, back == null ? () -> { } : back);
        for (Row row : fixed) menu.button(row.text(), row.icon(), row.action());
        for (Row row : rows.subList(current * PAGE, Math.min(rows.size(), (current + 1) * PAGE))) menu.button(row.text(), row.icon(), row.action());
        if (current > 0) menu.button(text(player, "window.prev"), null, () -> reopen.accept(current - 1));
        if (current < pages - 1) menu.button(text(player, "window.next"), null, () -> reopen.accept(current + 1));
        menu.show();
    }

    // ---- claim list ----

    String stateText(Player player, Lease state) { return text(player, "window.state." + state.state); }

    static String icon(LandClaim claim, boolean rented) {
        if (rented) return "textures/items/gold_ingot";
        if (claim.kind() == ClaimKind.FACILITY) return "textures/items/minecart_normal";
        return switch (claim.type() == null ? "" : claim.type()) {
            case "sub" -> "textures/items/map_filled";
            case "pvp" -> "textures/items/iron_sword";
            default -> "textures/items/bed_red";
        };
    }

    private void list(Player player, int page, String query) {
        UUID me = player.getUniqueId();
        List<LandClaim> owned = plugin.index().ownedBy(me).stream()
                .filter(claim -> LeaseInput.matches(query, claim.name(), claim.id().toString()))
                .sorted(Comparator.comparing(LandClaim::name, String.CASE_INSENSITIVE_ORDER)).toList();
        Set<UUID> ownedIds = new HashSet<>();
        owned.forEach(claim -> ownedIds.add(claim.id()));
        List<Row> rows = new ArrayList<>();
        for (LandClaim claim : owned) {
            Lease held = plugin.leases().leaseOf(claim.id());
            String label = held == null ? claim.name() : claim.name() + " §8(" + stateText(player, held) + ")";
            rows.add(new Row(label, icon(claim, false), () -> detail(player, claim.id(), page, query)));
        }
        for (Lease held : plugin.leases().allLeases()) {
            if (!me.toString().equals(held.tenant)) continue;
            UUID id = UUID.fromString(held.claimId);
            var found = plugin.getClaim(id);
            if (found.isEmpty() || ownedIds.contains(id) || !LeaseInput.matches(query, found.get().name(), held.claimId)) continue;
            rows.add(new Row(found.get().name() + " §8(" + text(player, "window.rented") + " " + stateText(player, held) + ")", icon(found.get(), true),
                    () -> detail(player, id, page, query)));
        }
        List<Row> fixed = List.of(
                new Row(text(player, "window.search"), "textures/ui/magnifyingGlass", () -> searchForm(player, query, q -> list(player, 0, q))),
                new Row(text(player, "window.leaseMenu"), "textures/items/emerald", () -> lease.menu(player)));
        String info = rows.isEmpty() ? text(player, "window.empty") : text(player, "window.listText", rows.size())
                + (query.isEmpty() ? "" : "\n" + text(player, "window.filter", query));
        paged(player, text(player, "window.title"), info, fixed, rows, page, p -> list(player, p, query), query.isEmpty() ? null : () -> list(player, 0, ""));
    }

    // ---- claim detail ----

    private void detail(Player player, UUID id, int page, String query) {
        var found = plugin.getClaim(id);
        if (found.isEmpty()) { list(player, page, query); return; }
        LandClaim claim = found.get();
        String language = plugin.texts().language(player);
        boolean mine = ClaimAccess.effectiveOwner(plugin.index(), id).filter(player.getUniqueId()::equals).isPresent();
        Lease held = plugin.leases().leaseOf(id);
        boolean tenant = held != null && player.getUniqueId().toString().equals(held.tenant);
        Runnable back = () -> list(player, page, query);
        var menu = menu(player, claim.displayName(language), plugin.texts().claimInfo(claim, language));
        menu.button(text(player, "window.back"), null, back);
        // The way there is tokaPortal's, and only a claim with a teleport point can be gone to.
        if (claim.details().teleport() != null && portal() != null) menu.button(text(player, "window.teleport"), "textures/items/ender_pearl", () -> goTo(player, claim));
        if (mine) {
            menu.button(text(player, "window.rename"), "textures/items/name_tag", () -> renameForm(player, claim));
            menu.button(text(player, "window.members"), "textures/ui/FriendsIcon", () -> membersForm(player, claim));
            menu.button(text(player, "window.createSub"), "textures/items/map_filled", () -> lease.subForm(player));
            if (claim.parentClaimId() != null) menu.button(text(player, "window.offer"), "textures/items/emerald", () -> lease.offerForm(player, id));
            menu.button(text(player, "window.delete"), "textures/ui/trash", () -> confirm(player, text(player, "window.delete"),
                    text(player, "window.deleteText", claim.name()), () -> run(player, "land del " + id)));
        }
        if (tenant) menu.button(text(player, "lw.release"), "textures/items/gold_ingot", () -> lease.rentalDetail(player, held));
        menu.show();
    }

    private void renameForm(Player player, LandClaim claim) {
        var window = new FormWindowCustom(text(player, "window.rename"));
        window.addElement(new ElementHeader(text(player, "window.rename")));
        window.addElement(new ElementInput(text(player, "window.newName"), "", claim.name()));
        custom(player, window, response -> {
            String name = response.getInputResponse(1) == null ? "" : response.getInputResponse(1).strip();
            if (!LeaseInput.commandSafe(name)) { player.sendMessage(text(player, "window.badName")); renameForm(player, claim); return; }
            run(player, "land rename " + name + " " + claim.id());
        });
    }

    private void membersForm(Player player, LandClaim claim) {
        var window = new FormWindowCustom(text(player, "window.members"));
        window.addElement(new ElementHeader(text(player, "window.members")));
        window.addElement(new ElementDropdown(text(player, "window.memberMode"),
                List.of(text(player, "window.memberAdd"), text(player, "window.memberDel"), text(player, "window.memberList"))));
        window.addElement(new ElementInput(text(player, "window.memberName"), "", ""));
        custom(player, window, response -> {
            int mode = response.getDropdownResponse(1).getElementID();
            String who = response.getInputResponse(2) == null ? "" : response.getInputResponse(2).strip();
            if (mode == 2) { run(player, "land group list " + claim.id()); return; }
            if (!LeaseInput.commandSafe(who)) { player.sendMessage(text(player, "window.badName")); membersForm(player, claim); return; }
            run(player, "land group " + (mode == 0 ? "add " : "del ") + who + " " + claim.id());
        });
    }

    private java.lang.reflect.Method portal() {
        var found = plugin.getServer().getPluginManager().getPlugin("tokaPortal");
        if (found == null || !found.isEnabled()) return null;
        try { return Class.forName("dev.toka.pl.tokaPortal.utils.Portal", true, found.getClass().getClassLoader()).getMethod("toLandClaim", Player.class, String.class); }
        catch (ReflectiveOperationException | LinkageError missing) { return null; }
    }

    private void goTo(Player player, LandClaim claim) {
        var go = portal();
        if (go == null) return;
        try { go.invoke(null, player, claim.id().toString()); }
        catch (ReflectiveOperationException | LinkageError failed) { player.sendMessage(text(player, "window.teleportFailed")); }
    }

    @EventHandler public void onResponse(PlayerFormRespondedEvent event) {
        Entry entry = open.remove(event.getWindow());
        if (entry == null || entry.player() != event.getPlayer() || event.wasClosed() || event.getResponse() == null) return;
        entry.handler().accept(event.getResponse());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        synchronized (open) { open.values().removeIf(entry -> entry.player() == event.getPlayer()); }
    }
}
