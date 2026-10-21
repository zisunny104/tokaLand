package dev.toka.land.protection;

import cn.nukkit.block.*;
import cn.nukkit.event.block.*;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import cn.nukkit.math.BlockFace;
import dev.toka.land.model.ClaimPermission;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PlayerProtectionListenerTest {
    @Test public void placeChecksPlacedBlockRatherThanSupportingBlockOrReplacement(){
        Block placed=new BlockAir(),support=new BlockAir(),replaced=new BlockAir();placed.x=30;support.x=29;replaced.x=30;
        var listener=new PlayerProtectionListener(()->true,(p,target,action)->{
            assertSame(placed,target);assertEquals(ClaimPermission.BUILD,action);return new PlayerProtectionListener.Decision(false,"DENIED");
        },(p,r)->{});
        var event=new BlockPlaceEvent(null,placed,replaced,support,null);listener.onPlace(event);assertTrue(event.isCancelled());
    }
    @Test public void cannotUncancelAnotherPluginsDenialAndStandbyDoesNotTakeOver(){
        AtomicInteger queries=new AtomicInteger();
        var listener=new PlayerProtectionListener(()->true,(p,target,a)->{queries.incrementAndGet();return new PlayerProtectionListener.Decision(true,"ALLOW");},(p,r)->{});
        var event=new BlockPlaceEvent(null,new BlockAir(),null,null,null);event.setCancelled();listener.onPlace(event);
        assertTrue(event.isCancelled());assertEquals(0,queries.get());
        listener=new PlayerProtectionListener(()->false,(p,target,a)->{fail("standby must not query");return null;},(p,r)->{});
        event=new BlockPlaceEvent(null,new BlockAir(),null,null,null);listener.onPlace(event);assertFalse(event.isCancelled());
    }
    @Test public void airClicksDoNotQueryPlaceholderButPhysicalAndBlockClicksDo(){
        AtomicInteger queries=new AtomicInteger();Block target=new BlockAir();target.x=64;
        var listener=new PlayerProtectionListener(()->true,(p,position,a)->{assertSame(target,position);assertEquals(ClaimPermission.INTERACT,a);queries.incrementAndGet();return new PlayerProtectionListener.Decision(false,"DENIED");},(p,r)->{});
        for(var action:PlayerInteractEvent.Action.values()){
            var event=new PlayerInteractEvent(null,null,target,BlockFace.UP,action);listener.onInteract(event);
            assertEquals(action!=PlayerInteractEvent.Action.LEFT_CLICK_AIR&&action!=PlayerInteractEvent.Action.RIGHT_CLICK_AIR,event.isCancelled());
        }
        assertEquals(3,queries.get());
    }
    @Test public void frameAndBreakUseActualEventTargets(){
        Block frame=new BlockAir(),broken=new BlockAir();frame.x=-12;broken.x=70;
        var listener=new PlayerProtectionListener(()->true,(p,target,a)->{
            assertTrue(target==frame&&a==ClaimPermission.INTERACT||target==broken&&a==ClaimPermission.BUILD);
            return new PlayerProtectionListener.Decision(false,"DENIED");
        },(p,r)->{});
        var drop=new ItemFrameDropItemEvent(null,frame,null,null);listener.onFrameDrop(drop);assertTrue(drop.isCancelled());
        var event=new BlockBreakEvent(null,broken,null,new Item[0]);listener.onBreak(event);assertTrue(event.isCancelled());
    }
}
