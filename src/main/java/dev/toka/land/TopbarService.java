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

/** Shows the personal claim hint through tokaDisplay; without it nothing is shown. */
final class TopbarService implements Listener {
    private static final String KEY = "tokaLand:claim";
    private final Main plugin;
    private final Map<UUID, String> shown = new HashMap<>();
    private Method show;
    private Method hide;

    TopbarService(Main plugin) {
        this.plugin = plugin;
        Plugin display = plugin.getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display == null || !display.isEnabled()) return;
        try {
            Class<?> api = Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader());
            show = api.getMethod("bossBar", Player.class, String.class, String.class, float.class, int.class, int.class);
            hide = api.getMethod("remove", Player.class, String.class);
        } catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().warning("tokaDisplay API 不相容，領地頂部提示停用：" + error.getClass().getSimpleName());
        }
    }

    @EventHandler public void onEnter(PlayerEnterClaimEvent event) { update(event.getPlayer(), event.getAt()); }

    @EventHandler public void onLeave(PlayerLeaveClaimEvent event) { update(event.getPlayer(), event.getAt()); }

    /** Runs after the tracker's leave events, so the hint is cleared last. */
    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) { remove(event.getPlayer()); }

    void removeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers().values()) remove(player);
    }

    private void update(Player player, Position at) {
        if (show == null || at.getLevel() == null || !Main.ownsLand() || !plugin.getConfig().getBoolean("display.player-land-topbar", true)) return;
        String language = player.getLocale().toLanguageTag();
        List<String> lines = plugin.findAllAt(at.getLevel().getFolderName(), at.getFloorX(), at.getFloorY(), at.getFloorZ())
                .stream().filter(claim -> claim.kind() == ClaimKind.PLAYER)
                .map(claim -> plugin.texts().message(language, "ui.land", claim.displayName(language), plugin.texts().ownerText(claim, language)))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (lines.isEmpty()) { remove(player); return; }
        String text = String.join("\n", lines);
        if (text.equals(shown.put(player.getUniqueId(), text))) return;
        try { show.invoke(null, player, KEY, text, 100f, 20, 40); }
        catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().warning("tokaDisplay API 失效，領地頂部提示停用：" + error.getClass().getSimpleName());
            show = null;
        }
    }

    private void remove(Player player) {
        shown.remove(player.getUniqueId());
        if (hide == null) return;
        try { hide.invoke(null, player, KEY); } catch (ReflectiveOperationException | LinkageError ignored) { }
    }
}
