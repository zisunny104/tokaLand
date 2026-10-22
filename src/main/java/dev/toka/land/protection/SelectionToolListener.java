package dev.toka.land.protection;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import dev.toka.land.Main;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Captures temporary selection corners with a configurable item; it never writes claims. */
public final class SelectionToolListener implements Listener {
    public record Point(String world,int x,int y,int z) { }
    public record Selection(Point first,Point second) { }

    private final Main plugin;
    private final int itemId;
    private final int itemDamage;
    private final Map<UUID,Selection> selections=new HashMap<>();

    public SelectionToolListener(Main plugin,Item configuredItem) {
        this.plugin=plugin;
        this.itemId=configuredItem.getId();
        this.itemDamage=configuredItem.getDamage();
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player=event.getPlayer();
        if(!player.hasPermission("tokaland.admin")||event.getItem()==null
            ||event.getItem().getId()!=itemId||event.getItem().getDamage()!=itemDamage)return;
        if(event.getAction()!=PlayerInteractEvent.Action.LEFT_CLICK_BLOCK
            &&event.getAction()!=PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)return;
        var block=event.getBlock();
        if(block==null||block.getLevel()==null)return;
        Point point=new Point(block.getLevel().getFolderName(),block.getFloorX(),block.getFloorY(),block.getFloorZ());
        Selection previous=selections.get(player.getUniqueId());
        boolean first=event.getAction()==PlayerInteractEvent.Action.LEFT_CLICK_BLOCK;
        Selection updated=first?new Selection(point,previous==null?null:previous.second())
            :new Selection(previous==null?null:previous.first(),point);
        selections.put(player.getUniqueId(),updated);
        event.setCancelled();
        plugin.sendSelectionPoint(player,first,point);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event){forget(event.getPlayer().getUniqueId());}

    public Selection get(UUID playerId){return selections.get(playerId);}
    public void forget(UUID playerId){selections.remove(playerId);}
}
