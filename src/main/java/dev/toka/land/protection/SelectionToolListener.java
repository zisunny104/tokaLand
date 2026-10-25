package dev.toka.land.protection;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.item.Item;
import dev.toka.land.Main;
import dev.toka.land.model.LandSelection;
import dev.toka.land.model.ClaimDetails.TeleportPoint;
import java.util.Map;
import java.util.UUID;

/** Captures temporary selection corners with a configurable item; it never writes claims. */
public final class SelectionToolListener implements Listener {
    public record Point(String world,int x,int y,int z) { }
    public record Selection(Point first,Point second,boolean ignoreHeight,TeleportPoint teleport) {
        public LandSelection geometry(){
            if(first==null||second==null||!first.world().equals(second.world()))throw new IllegalStateException("SELECTION_REQUIRED");
            return new LandSelection(first.world(),first.x(),first.y(),first.z(),second.x(),second.y(),second.z(),ignoreHeight);
        }
    }

    private final Main plugin;
    private final int itemId;
    private final int itemDamage;
    private final Map<UUID,Selection> selections=new java.util.concurrent.ConcurrentHashMap<>();

    public SelectionToolListener(Main plugin,Item configuredItem) {
        this.plugin=plugin;
        this.itemId=configuredItem.getId();
        this.itemDamage=configuredItem.getDamage();
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player=event.getPlayer();
        if(!Main.ownsLand()||!player.hasPermission("tokaland.use")||event.getItem()==null
            ||event.getItem().getId()!=itemId||event.getItem().getDamage()!=itemDamage)return;
        if(event.getAction()!=PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)return;
        var block=event.getBlock();
        if(block==null||block.getLevel()==null)return;
        Point point=new Point(block.getLevel().getFolderName(),block.getFloorX(),block.getFloorY(),block.getFloorZ());
        event.setCancelled();
        capture(player,point,false);
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event){
        if(!Main.ownsLand()||!event.getPlayer().hasPermission("tokaland.use")||event.getItem()==null
                ||event.getItem().getId()!=itemId||event.getItem().getDamage()!=itemDamage)return;
        event.setCancelled();var block=event.getBlock();
        capture(event.getPlayer(),new Point(block.getLevel().getFolderName(),block.getFloorX(),block.getFloorY(),block.getFloorZ()),true);
    }
    private void capture(Player player,Point point,boolean first){
        try{
            boolean ignoreHeight=plugin.trading().ignoreHeight(player,point);Selection previous=selections.get(player.getUniqueId());
            Point other=previous==null?null:first?previous.second():previous.first();
            if(other!=null&&!other.world().equals(point.world()))other=null;
            var updated=first?new Selection(point,other,ignoreHeight,null):new Selection(other,point,ignoreHeight,null);
            selections.put(player.getUniqueId(),updated);plugin.texts().sendSelectionPoint(player,first,point);announce(player,updated);
        }catch(RuntimeException failure){player.sendMessage(plugin.texts().text(player,"purchase.unavailable",failure.getMessage()));}
    }
    public Selection toggleHeight(UUID player){
        Selection old=selections.get(player);if(old==null)throw new IllegalStateException("SELECTION_REQUIRED");
        var changed=new Selection(old.first(),old.second(),!old.ignoreHeight(),null);selections.put(player,changed);
        plugin.getServer().getPlayer(player).ifPresent(online->announce(online,changed));return changed;
    }
    /** With both corners set: the size and price line in chat, and the box of the selection drawn for this player. */
    public void announce(Player player,Selection draft){
        if(draft.first()==null||draft.second()==null||!draft.first().world().equals(draft.second().world()))return;
        try{plugin.texts().sendSelectionSummary(player,plugin.trading().preview(player,draft));}
        catch(RuntimeException failure){player.sendMessage(plugin.texts().text(player,"purchase.unavailable",failure.getMessage()));}
        dev.toka.land.BoundingBoxLink.show(plugin,player,draft.geometry(),draft.ignoreHeight(),"land-selection");
    }
    public void teleport(UUID player,TeleportPoint point){
        Selection old=selections.get(player);if(old==null)throw new IllegalStateException("SELECTION_REQUIRED");
        if(!old.geometry().contains(point.world(),(int)Math.floor(point.x()),(int)Math.floor(point.y()),(int)Math.floor(point.z())))throw new IllegalStateException("TELEPORT_OUTSIDE_SELECTION");
        selections.put(player,new Selection(old.first(),old.second(),old.ignoreHeight(),point));
    }
    public void forget(UUID player,Selection expected){selections.remove(player,expected);}

    @EventHandler public void onQuit(PlayerQuitEvent event){forget(event.getPlayer().getUniqueId());}

    public Selection get(UUID playerId){return selections.get(playerId);}
    public void forget(UUID playerId){selections.remove(playerId);}
}
