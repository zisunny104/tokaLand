package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.Plugin;
import dev.toka.land.event.PlayerEnterClaimEvent;
import dev.toka.land.event.PlayerLeaveClaimEvent;
import dev.toka.land.model.ClaimKind;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

/**
 * Shows the claim the player stands in at the top, through tokaDisplay: it stays for as long as the player is inside and is taken
 * away when they step out. Without tokaDisplay nothing is shown.
 */
final class TopbarService implements Listener {
    private static final String KEY = "tokaLand:claim";
    private final Main plugin;
    private final Map<UUID, String> shown = new HashMap<>();
    private Method pin;
    private Method unpin;

    TopbarService(Main plugin) {
        this.plugin = plugin;
        connect();
    }

    /** tokaDisplay may start after this plugin or be reloaded, which drops what was registered with it; connect again whenever it comes up. */
    @EventHandler public void onDisplayEnabled(cn.nukkit.event.plugin.PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("tokaDisplay")) { shown.clear(); plugin.getServer().getScheduler().scheduleTask(plugin, this::connect); }
    }

    private void connect() {
        pin = null;
        unpin = null;
        Plugin display = plugin.getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display == null || !display.isEnabled()) return;
        try {
            Class<?> api = Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader());
            api.getMethod("registerSection", String.class, String.class, Function.class).invoke(null, "tokaLand", "area", (Function<Player, String>) this::facilityLight);
            pin = api.getMethod("pin", Player.class, String.class, String.class, int.class, int.class, float.class, String.class);
            unpin = api.getMethod("unpin", Player.class, String.class);
        } catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().warning("tokaDisplay API 不相容，領地頂部提示停用：" + error.getClass().getSimpleName());
        }
    }

    @EventHandler public void onEnter(PlayerEnterClaimEvent event) { update(event.getPlayer(), event.getAt()); }

    @EventHandler public void onLeave(PlayerLeaveClaimEvent event) { update(event.getPlayer(), event.getAt()); }

    /** Runs after the tracker's leave events, so the hint is cleared last. */
    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) { remove(event.getPlayer()); }

    /** Shows each player what is right for where they stand now, whatever events have or have not come: it fixes a hint that went missing or is out of date. */
    void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers().values()) {
            if (player.isOnline() && player.spawned) update(player, player.getPosition());
        }
    }

    void removeAll() {
        Plugin display = plugin.getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display != null) {
            try { Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader()).getMethod("unregisterSections", String.class).invoke(null, "tokaLand"); }
            catch (ReflectiveOperationException | LinkageError ignored) { }
        }
        for (Player player : plugin.getServer().getOnlinePlayers().values()) remove(player);
    }

    private void update(Player player, Position at) {
        if (pin == null || at.getLevel() == null || !Main.ownsLand() || !plugin.getConfig().getBoolean("display.player-land-topbar", true)) return;
        String language = player.getLocale() == null ? plugin.getConfig().getString("language", "zh-TW") : player.getLocale().toLanguageTag();
        List<String> lines = plugin.findAllAt(at.getLevel().getFolderName(), at.getFloorX(), at.getFloorY(), at.getFloorZ())
                .stream().filter(claim -> claim.kind() == ClaimKind.PLAYER)
                .map(claim -> plugin.texts().message(language, "ui.land", claim.displayName(language), plugin.texts().ownerText(claim, language)))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (lines.isEmpty()) { remove(player); return; }
        String text = String.join("\n", lines);
        if (text.equals(shown.put(player.getUniqueId(), text))) return;
        try { pin.invoke(null, player, KEY, text, 0, 20, -1f, ""); }
        catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().warning("tokaDisplay API 失效，領地頂部提示停用：" + error.getClass().getSimpleName());
            pin = null;
        }
    }

    /** The facility light of the sidebar: lit with the facility's type while the player stands in a facility, grey otherwise. */
    private String facilityLight(Player player) {
        if (!Main.ownsLand() || player.getLevel() == null) return null;
        var type = plugin.findAllAt(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ()).stream()
                .filter(claim -> claim.kind() == ClaimKind.FACILITY).map(claim -> claim.type()).filter(name -> name != null && !name.isEmpty()).findFirst();
        return type.map(name -> "§6⧯" + name + "§r").orElse("§7⧯§r");
    }

    private void remove(Player player) {
        shown.remove(player.getUniqueId());
        if (unpin == null) return;
        try { unpin.invoke(null, player, KEY); } catch (ReflectiveOperationException | LinkageError ignored) { }
    }
}
