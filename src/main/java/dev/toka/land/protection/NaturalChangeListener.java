package dev.toka.land.protection;

import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockIgniteEvent;
import cn.nukkit.event.block.BlockSpreadEvent;
import cn.nukkit.event.block.LiquidFlowEvent;
import cn.nukkit.event.entity.CreatureSpawnEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.entity.EntityInteractEvent;
import cn.nukkit.event.level.StructureGrowEvent;
import cn.nukkit.event.weather.LightningStrikeEvent;
import dev.toka.land.Main;
import cn.nukkit.level.Position;

/** Boundary-aware protection for non-player world changes. */
public final class NaturalChangeListener implements Listener {
    private final Main plugin;
    public NaturalChangeListener(Main plugin){this.plugin=plugin;}

    @EventHandler(ignoreCancelled=true)
    public void onLiquidFlow(LiquidFlowEvent event){
        if(plugin.crossesLandBoundary(event.getSource(),event.getTo(),"protection.liquids.prevent-boundary-flow"))event.setCancelled();
    }

    @EventHandler(ignoreCancelled=true)
    public void onBlockSpread(BlockSpreadEvent event){
        if(plugin.crossesLandBoundary(event.getSource(),event.getBlock(),"protection.growth.prevent-boundary-crossing"))event.setCancelled();
    }

    @EventHandler(ignoreCancelled=true)
    public void onStructureGrow(StructureGrowEvent event){
        if(event.getBlockList()==null||event.getBlockList().isEmpty())return;
        for(var block:event.getBlockList()){
            if(plugin.crossesLandBoundary(event.getBlock(),block,"protection.growth.prevent-boundary-crossing")){
                event.setCancelled();return;
            }
        }
    }

    @EventHandler(ignoreCancelled=true)
    public void onMobBlockInteract(EntityInteractEvent event){
        if(plugin.getConfig().getBoolean("protection.entities.block-mob-interactions",true)
            &&plugin.protectedEnvironment(event.getBlock()))event.setCancelled();
    }

    @EventHandler(ignoreCancelled=true)
    public void onLightningStrike(LightningStrikeEvent event){
        if(event.getLightning() instanceof cn.nukkit.entity.Entity lightning
            &&plugin.getConfig().getBoolean("protection.lightning.protect-claims",true)
            &&plugin.protectedEnvironment(lightning.getPosition()))event.setCancelled();
    }

    @EventHandler(ignoreCancelled=true)
    public void onLightningIgnite(BlockIgniteEvent event){
        if(event.getCause()==BlockIgniteEvent.BlockIgniteCause.LIGHTNING
            &&plugin.getConfig().getBoolean("protection.lightning.protect-claims",true)
            &&plugin.protectedEnvironment(event.getBlock()))event.setCancelled();
    }

    @EventHandler(ignoreCancelled=true)
    public void onLightningDamage(EntityDamageEvent event){
        if(event.getCause()==EntityDamageEvent.DamageCause.LIGHTNING
            &&plugin.getConfig().getBoolean("protection.lightning.protect-claims",true)
            &&plugin.protectedEnvironment(event.getEntity().getPosition()))event.setCancelled();
    }

    @EventHandler(ignoreCancelled=true)
    public void onLightningTransformation(CreatureSpawnEvent event){
        if(event.getReason()==CreatureSpawnEvent.SpawnReason.LIGHTNING
            &&plugin.getConfig().getBoolean("protection.lightning.protect-claims",true)
            &&plugin.protectedEnvironment(event.getPosition()))event.setCancelled();
    }
}
