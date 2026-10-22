package dev.toka.land.protection;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.plugin.Plugin;
import dev.toka.land.Main;
import java.lang.reflect.Method;

/**
 * Re-allows player damage inside PvP claims after other protections cancelled it. Mobs and other damage stay as they are.
 * Works on its own; when tokaProtect is running its pvp rule it owns the decision (it knows about enforced region settings),
 * so this steps aside, and takes over again if tokaProtect stops or its pvp feature trips.
 */
public final class PvpListener implements Listener {
    private final Main plugin;
    private volatile Method protectHandlesPvp;
    private volatile boolean looked;

    public PvpListener(Main plugin) { this.plugin = plugin; }

    private boolean protectHandles() {
        if (!looked || protectHandlesPvp == null) {
            Plugin protect = plugin.getServer().getPluginManager().getPlugin("tokaProtect");
            protectHandlesPvp = null;
            if (protect != null && protect.isEnabled()) {
                try { protectHandlesPvp = Class.forName("dev.toka.protect.Main", true, protect.getClass().getClassLoader()).getMethod("handlesPvp"); }
                catch (ReflectiveOperationException | LinkageError missing) { protectHandlesPvp = null; }
            }
            looked = true;
        }
        Method check = protectHandlesPvp;
        if (check == null) return false;
        try { return (Boolean) check.invoke(null); }
        catch (ReflectiveOperationException | LinkageError gone) { protectHandlesPvp = null; return false; }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player attacker)) return;
        if (victim == attacker || protectHandles() || !plugin.queries().pvpAllowed(victim, attacker)) return;
        event.setCancelled(false);
    }
}
