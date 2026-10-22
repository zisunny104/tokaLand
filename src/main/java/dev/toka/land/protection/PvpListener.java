package dev.toka.land.protection;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import dev.toka.land.Main;

/** Re-allows player damage inside PvP claims after other protections cancelled it. Mobs and other damage stay as they are. */
public final class PvpListener implements Listener {
    private final Main plugin;

    public PvpListener(Main plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player attacker)) return;
        if (victim == attacker || !plugin.queries().pvpAllowed(victim, attacker)) return;
        event.setCancelled(false);
    }
}
