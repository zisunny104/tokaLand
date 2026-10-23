package dev.toka.land.form;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;
import dev.toka.land.Main;
import dev.toka.land.model.LandClaim;
import java.util.*;
import java.util.function.IntConsumer;

/** The player's own claims as a window: a paged list and a read-only detail page. Buttons are matched by index, never by text. */
public final class LandWindow implements Listener {
    private static final int PAGE = 8;
    private record Entry(Player player, IntConsumer handler) {}
    private final Main plugin;
    private final Map<FormWindow, Entry> open = Collections.synchronizedMap(new WeakHashMap<>());

    public LandWindow(Main plugin) { this.plugin = plugin; }

    public void open(Player player) { list(player, 0); }

    private String text(Player player, String key, Object... args) { return plugin.texts().text(player, key, args); }

    private void show(Player player, FormWindowSimple window, IntConsumer handler) {
        open.put(window, new Entry(player, handler));
        player.showFormWindow(window);
    }

    private void list(Player player, int page) {
        List<LandClaim> owned = plugin.index().ownedBy(player.getUniqueId()).stream()
                .sorted(Comparator.comparing(LandClaim::name, String.CASE_INSENSITIVE_ORDER)).toList();
        int pages = Math.max(1, (owned.size() + PAGE - 1) / PAGE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        var shown = owned.subList(current * PAGE, Math.min(owned.size(), (current + 1) * PAGE));
        var window = new FormWindowSimple(text(player, "window.title"),
                owned.isEmpty() ? text(player, "window.empty") : text(player, "window.listText", owned.size(), current + 1, pages));
        List<Runnable> actions = new ArrayList<>();
        for (LandClaim claim : shown) { window.addButton(new ElementButton(claim.name())); actions.add(() -> detail(player, claim.id(), current)); }
        if (current > 0) { window.addButton(new ElementButton(text(player, "window.prev"))); actions.add(() -> list(player, current - 1)); }
        if (current < pages - 1) { window.addButton(new ElementButton(text(player, "window.next"))); actions.add(() -> list(player, current + 1)); }
        window.addButton(new ElementButton(text(player, "window.close")));
        actions.add(() -> { });
        show(player, window, index -> { if (index >= 0 && index < actions.size()) actions.get(index).run(); });
    }

    private void detail(Player player, UUID id, int page) {
        var found = plugin.getClaim(id);
        if (found.isEmpty()) { list(player, page); return; }
        LandClaim claim = found.get();
        String language = plugin.texts().language(player);
        var window = new FormWindowSimple(claim.displayName(language), text(player, "tools.info", claim.id(), claim.displayName(language),
                plugin.texts().ownerText(claim, language), claim.type(),
                claim.accessPolicy().mode() + (claim.accessPolicy().locked() ? "+LOCK" : ""), claim.selections().size()));
        // The way there is tokaPortal's, and only a claim with a teleport point can be gone to.
        boolean teleport = claim.details().teleport() != null && portal() != null;
        if (teleport) window.addButton(new ElementButton(text(player, "window.teleport")));
        window.addButton(new ElementButton(text(player, "window.back")));
        window.addButton(new ElementButton(text(player, "window.close")));
        show(player, window, index -> {
            if (teleport && index == 0) goTo(player, claim);
            else if (index == (teleport ? 1 : 0)) list(player, page);
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
        if (entry == null || entry.player() != event.getPlayer() || event.wasClosed()
                || !(event.getResponse() instanceof FormResponseSimple response)) return;
        entry.handler().accept(response.getClickedButtonId());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        synchronized (open) { open.values().removeIf(entry -> entry.player() == event.getPlayer()); }
    }
}
