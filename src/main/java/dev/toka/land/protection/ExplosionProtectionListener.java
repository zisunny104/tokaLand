package dev.toka.land.protection;

import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockExplodeEvent;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.entity.EntityExplosionPrimeEvent;
import cn.nukkit.level.Position;
import cn.nukkit.scheduler.TaskHandler;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Captures the final entity explosion force before Nukkit creates its explosion,
 * then checks the complete damage/knockback AABB before the explosion is applied.
 */
public final class ExplosionProtectionListener implements Listener {
    @FunctionalInterface public interface Gate { boolean active(); }
    @FunctionalInterface public interface ExplosionCheck {
        boolean cancel(Entity source, Position center, double force);
    }
    private record Capture(double force, long capturedAtTick) { }

    private final Gate active;
    private final Gate cancelUnknownRange;
    private final ExplosionCheck check;
    private final java.util.function.LongSupplier currentTick;
    private final Map<Entity,Capture> forces = new IdentityHashMap<>();
    private final java.util.function.Consumer<String> warning;
    private boolean warnedMissingForce;
    private boolean warnedMissingBlockRange;

    public ExplosionProtectionListener(Gate active, Gate cancelUnknownRange, ExplosionCheck check,
                                       java.util.function.LongSupplier currentTick,
                                       java.util.function.Consumer<String> warning) {
        this.active=active;
        this.cancelUnknownRange=cancelUnknownRange;
        this.check=check;
        this.currentTick=currentTick;
        this.warning=warning;
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onPrime(EntityExplosionPrimeEvent event) {
        if(!active.active())return;
        prune();
        double force=event.getForce();
        if(Double.isFinite(force)&&force>=0)forces.put(event.getEntity(),new Capture(force,currentTick.getAsLong()));
    }

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if(!active.active()||!cancelUnknownRange.active())return;
        Capture capture=forces.remove(event.getEntity());
        if(capture==null||currentTick.getAsLong()-capture.capturedAtTick()>1) {
            // Without the core's effective force, the entity damage/knockback range is unknown.
            event.setCancelled();
            if(!warnedMissingForce){warnedMissingForce=true;warning.accept(
                "已取消缺少爆炸範圍資料的實體爆炸；請檢查自訂爆炸是否呼叫 EntityExplosionPrimeEvent。" );}
            return;
        }
        if(check.cancel(event.getEntity(),event.getPosition(),capture.force()))event.setCancelled();
    }

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if(!active.active()||!cancelUnknownRange.active())return;
        // The core event does not expose force or the entity-damage/knockback AABB.
        event.setCancelled();
        if(!warnedMissingBlockRange){warnedMissingBlockRange=true;warning.accept(
            "已取消缺少完整影響範圍資料的區塊爆炸；目前採保護優先策略。" );}
    }

    /** Remove abandoned captures from cancelled or otherwise unused prime events. */
    public void prune() {
        long tick=currentTick.getAsLong();
        forces.entrySet().removeIf(entry->tick-entry.getValue().capturedAtTick()>1);
    }
}
