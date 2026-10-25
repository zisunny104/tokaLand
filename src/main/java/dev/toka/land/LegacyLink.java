package dev.toka.land;

import cn.nukkit.event.Event;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.HandlerList;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.plugin.Plugin;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Method;

/**
 * Listens to tokaProfiles, which alone decides who an old name is, and replaces the names it announces. Without tokaProfiles nothing
 * is replaced and old names simply stay unresolved.
 */
final class LegacyLink implements Listener {
    private final Main plugin;
    private boolean listening;
    /** Apart from this class, so that removing the event handler leaves the plugin-enable handler alone. */
    private final Listener announcements = new Listener() { };

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
                Class<?> event = profiles.getClass().getClassLoader().loadClass("dev.toka.profiles.event.LegacyResolvedEvent");
                Method oldName = event.getMethod("getOldName"), uuid = event.getMethod("getUuid");
                plugin.getServer().getPluginManager().registerEvent(event.asSubclass(Event.class), announcements, EventPriority.NORMAL, (listener, fired) -> {
                    if (!event.isInstance(fired)) return;
                    try { replace(Map.of((String) oldName.invoke(fired), (UUID) uuid.invoke(fired))); }
                    catch (ReflectiveOperationException | ClassCastException unavailable) { plugin.texts().warn("log.legacyUnavailable", unavailable.getClass().getSimpleName()); }
                }, plugin);
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

    /** Stops listening; called when the plugin is disabled. */
    void disconnect() {
        HandlerList.unregisterAll(announcements);
        listening = false;
    }

    @EventHandler public void enabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("tokaProfiles")) {
            disconnect();
            plugin.getServer().getScheduler().scheduleTask(plugin, this::connect);
        }
    }
}
