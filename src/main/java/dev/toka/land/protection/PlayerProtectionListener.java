package dev.toka.land.protection;

import cn.nukkit.Player;
import cn.nukkit.entity.item.EntityArmorStand;
import cn.nukkit.event.*;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.level.Position;
import com.google.gson.JsonParser;
import dev.toka.land.Main;
import dev.toka.land.model.ClaimPermission;
import java.util.function.*;

/** Player-only legacy protection boundary. Selection tools and global simulation rules are separate work. */
public final class PlayerProtectionListener implements Listener {
    record Decision(boolean allowed,String reason){}
    @FunctionalInterface interface Check {Decision evaluate(Player player,Position target,ClaimPermission action);}
    private final BooleanSupplier active;
    private final Check check;
    private final BiConsumer<Player,String> denied;

    public PlayerProtectionListener(Main plugin){
        this(Main::ownsLand,(player,target,action)->{
            if(target==null||target.getLevel()==null)return new Decision(false,"INVALID_TARGET");
            var answer=JsonParser.parseString(Main.queryProtectionJson(player,target.getLevel().getFolderName(),
                target.getFloorX(),target.getFloorY(),target.getFloorZ(),action.name())).getAsJsonObject();
            return new Decision(answer.get("status").getAsString().equals("ALLOW")&&answer.get("allowed").getAsBoolean(),answer.get("reason").getAsString());
        },(player,reason)->{if(player!=null)player.sendMessage(plugin.text(player,"ui.denied",reason));});
    }
    PlayerProtectionListener(BooleanSupplier active,Check check,BiConsumer<Player,String> denied){
        this.active=active;this.check=check;this.denied=denied;
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
    public void onArmorStandDamage(EntityDamageByEntityEvent event){
        // Confirmed behavior: Area also participates; evaluate all claims at the actual entity position.
        if(event.getDamager() instanceof Player player&&event.getEntity() instanceof EntityArmorStand)
            enforce(event,player,event.getEntity().getPosition(),ClaimPermission.BUILD);
    }
    @EventHandler(ignoreCancelled=true)
    public void onFrameDrop(ItemFrameDropItemEvent event){
        Position target=event.getItemFrame()!=null?event.getItemFrame():event.getBlock();
        enforce(event,event.getPlayer(),target,ClaimPermission.INTERACT);
    }
}
