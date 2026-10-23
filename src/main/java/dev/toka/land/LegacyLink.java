package dev.toka.land;

import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.plugin.Plugin;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Listens to tokaProfiles, which alone decides who an old name is, and replaces the names it announces. Without tokaProfiles nothing
 * is replaced and old names simply stay unresolved.
 */
final class LegacyLink implements Listener {
    private final Main plugin;
    private boolean listening;

    LegacyLink(Main plugin) { this.plugin = plugin; }

    /** What has been announced so far, then everything announced later. */
    void connect() {
        Plugin profiles = plugin.getServer().getPluginManager().getPlugin("tokaProfiles");
        if (profiles == null || !profiles.isEnabled()) return;
        try {
            Class<?> type = profiles.getClass();
            @SuppressWarnings("unchecked") Map<String, UUID> resolved = (Map<String, UUID>) type.getMethod("legacyResolved").invoke(null);
            replace(resolved);
            if (!listening) {
                type.getMethod("onLegacyResolved", BiConsumer.class).invoke(null, (BiConsumer<String, UUID>) (name, uuid) -> replace(Map.of(name, uuid)));
                listening = true;
            }
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            plugin.texts().warn("log.legacyUnavailable", unavailable.getClass().getSimpleName());
        }
    }

    private void replace(Map<String, UUID> resolved) {
        if (resolved.isEmpty()) return;
        plugin.lands().replaceLegacyNames(resolved).whenComplete((count, failure) -> {
            if (failure != null) plugin.texts().warn("log.legacyFailed", failure.getCause());
            else if (count > 0) plugin.texts().info("log.legacyReplaced", count);
        });
    }

    @EventHandler public void enabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("tokaProfiles")) {
            listening = false;
            plugin.getServer().getScheduler().scheduleTask(plugin, this::connect);
        }
    }
}
