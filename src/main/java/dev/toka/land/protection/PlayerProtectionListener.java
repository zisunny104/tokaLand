package dev.toka.land.protection;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.event.*;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.player.PlayerInteractEntityEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.level.Position;
import com.google.gson.JsonParser;
import dev.toka.land.Main;
import dev.toka.land.model.ClaimPermission;
import java.util.function.*;

/** Player and entity interaction boundary for Land protection. */
public final class PlayerProtectionListener implements Listener {
    record Decision(boolean allowed,String reason){}
    @FunctionalInterface interface Check {Decision evaluate(Player player,Position target,ClaimPermission action);}
    private final BooleanSupplier active;
    private final Check check;
    private final BiConsumer<Player,String> denied;
    private final Main plugin;

    public PlayerProtectionListener(Main plugin){
        this(plugin,Main::ownsLand,(player,target,action)->{
            if(target==null||target.getLevel()==null)return new Decision(false,"INVALID_TARGET");
            var answer=JsonParser.parseString(Main.queryProtectionJson(player,target.getLevel().getFolderName(),
                target.getFloorX(),target.getFloorY(),target.getFloorZ(),action.name())).getAsJsonObject();
            return new Decision(answer.get("status").getAsString().equals("ALLOW")&&answer.get("allowed").getAsBoolean(),answer.get("reason").getAsString());
        },(player,reason)->{if(player!=null)player.sendMessage(plugin.texts().text(player,"ui.denied",reason));});
    }
    PlayerProtectionListener(BooleanSupplier active,Check check,BiConsumer<Player,String> denied){
        this(null,active,check,denied);
    }
    private PlayerProtectionListener(Main plugin,BooleanSupplier active,Check check,BiConsumer<Player,String> denied){
        this.plugin=plugin;this.active=active;this.check=check;this.denied=denied;
    }
    private void enforce(Event event,Player player,Position target,ClaimPermission action){
        if(event.isCancelled()||!active.getAsBoolean())return;
        Decision result=check.evaluate(player,target,action);
        if(!result.allowed()){event.setCancelled();denied.accept(player,result.reason());}
    }
    @EventHandler(ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event){enforce(event,event.getPlayer(),event.getBlock(),ClaimPermission.BUILD);}
    @EventHandler(ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent event){enforce(event,event.getPlayer(),event.getBlock(),ClaimPermission.BUILD);}
    @EventHandler(ignoreCancelled=true)
    public void onInteract(PlayerInteractEvent event){
        switch(event.getAction()){
            case LEFT_CLICK_BLOCK,RIGHT_CLICK_BLOCK,PHYSICAL -> enforce(event,event.getPlayer(),event.getBlock(),ClaimPermission.INTERACT);
            // Nukkit supplies a placeholder block at 0,0,0 for air clicks. It is not the action target.
            case LEFT_CLICK_AIR,RIGHT_CLICK_AIR -> { }
        }
    }
    @EventHandler(ignoreCancelled=true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event){
        if(event.getEntity()!=null)enforce(event,event.getPlayer(),event.getEntity().getPosition(),ClaimPermission.INTERACT);
    }
    @EventHandler(ignoreCancelled=true)
    public void onEntityDamage(EntityDamageByEntityEvent event){
        if(event.getEntity() instanceof Player)return; // PvP is governed by its own server policy.
        Player actor=null;
        Entity damager=event.getDamager();
        if(damager instanceof Player player)actor=player;
        else if(plugin!=null&&damager instanceof EntityProjectile){
            var attribution=ExplosionAttribution.resolve(damager,32);
            if(attribution.confirmed())actor=plugin.getServer().getOnlinePlayers().values().stream()
                .filter(candidate->candidate.getUniqueId().equals(attribution.playerId())).findFirst().orElse(null);
        }
        if(actor!=null){
            enforce(event,actor,event.getEntity().getPosition(),ClaimPermission.BUILD);
            return;
        }
        // Never infer an owner for mobs, offline shooters or unresolved projectile sources.
        if(plugin!=null&&plugin.getConfig().getBoolean("protection.entities.prevent-unattributed-damage",true)
            &&plugin.queries().protectedEnvironment(event.getEntity().getPosition()))event.setCancelled();
    }
    @EventHandler(ignoreCancelled=true)
    public void onFrameDrop(ItemFrameDropItemEvent event){
        Position target=event.getItemFrame()!=null?event.getItemFrame():event.getBlock();
        enforce(event,event.getPlayer(),target,ClaimPermission.INTERACT);
    }
}
