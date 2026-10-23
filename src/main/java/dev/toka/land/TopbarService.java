package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.plugin.Plugin;
import dev.toka.land.model.ClaimKind;
import java.util.*;
import java.util.function.Function;

/**
 * The claim the player stands in, as a line of tokaDisplay's information block: it stays while the player is inside and is gone once
 * they step out. Without tokaDisplay nothing is shown.
 */
final class TopbarService implements Listener {
    private static final String SECTION = "area";
    private final Main plugin;

    TopbarService(Main plugin) {
        this.plugin = plugin;
        register();
    }

    @EventHandler public void onEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("tokaDisplay")) register();
    }

    private void register() {
        Plugin display = plugin.getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display == null || !display.isEnabled()) return;
        try {
            Class<?> api = Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader());
            api.getMethod("registerSection", String.class, String.class, Function.class).invoke(null, "tokaLand", SECTION, (Function<Player, String>) this::line);
        } catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().warning("tokaDisplay API 不相容，領地提示停用：" + error.getClass().getSimpleName());
        }
    }

    /** The text for this player now; null when they are not inside a player's claim. */
    private String line(Player player) {
        if (!Main.ownsLand() || !plugin.getConfig().getBoolean("display.player-land-topbar", true) || player.getLevel() == null) return null;
        String language = player.getLocale() == null ? plugin.getConfig().getString("language", "zh-TW") : player.getLocale().toLanguageTag();
        List<String> lines = plugin.findAllAt(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ())
                .stream().filter(claim -> claim.kind() == ClaimKind.PLAYER)
                .map(claim -> plugin.texts().message(language, "ui.land", claim.displayName(language), plugin.texts().ownerText(claim, language)))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    void removeAll() {
        Plugin display = plugin.getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display == null) return;
        try {
            Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader()).getMethod("unregisterSections", String.class).invoke(null, "tokaLand");
        } catch (ReflectiveOperationException | LinkageError ignored) { }
    }
}
