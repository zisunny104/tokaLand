package dev.toka.land.protection;

import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class ActorAttributionTest {
    private static final class Node {
        final ActorAttribution.Kind kind;final UUID id;Node source;
        Node(ActorAttribution.Kind kind,UUID id,Node source){this.kind=kind;this.id=id;this.source=source;}
    }
    private static final ActorAttribution.Evidence<Node> EVIDENCE=new ActorAttribution.Evidence<>(){
        public ActorAttribution.Kind kind(Node n){return n.kind;}
        public UUID playerId(Node n){return n.id;}
        public Node source(Node n){return n.source;}
    };
    @Test public void explicitTntAndProjectileChainPreservesTheActualPlayer(){
        UUID id=UUID.randomUUID();Node player=new Node(ActorAttribution.Kind.PLAYER,id,null);
        Node arrow=new Node(ActorAttribution.Kind.PROJECTILE,null,player);
        Node tnt=new Node(ActorAttribution.Kind.TNT,null,new Node(ActorAttribution.Kind.TNT,null,arrow));
        var result=ActorAttribution.resolve(tnt,EVIDENCE,4);
        assertTrue(result.confirmed());assertEquals(id,result.playerId());assertEquals(4,result.inspected());
    }
    @Test public void missingCoreSourceDoesNotGuessAPlacementOwnerOrNearbyPlayer(){
        var result=ActorAttribution.resolve(new Node(ActorAttribution.Kind.TNT,null,null),EVIDENCE,8);
        assertFalse(result.confirmed());assertNull(result.playerId());assertEquals(ActorAttribution.Reason.MISSING_SOURCE,result.reason());
        assertEquals(ActorAttribution.Reason.MISSING_SOURCE,ActorAttribution.resolve(null,EVIDENCE,8).reason());
    }
    @Test public void unsupportedEntitiesCannotSmuggleAPlayerSource(){
        Node unsupported=new Node(ActorAttribution.Kind.UNKNOWN,null,new Node(ActorAttribution.Kind.PLAYER,UUID.randomUUID(),null));
        assertEquals(ActorAttribution.Reason.UNSUPPORTED_SOURCE,ActorAttribution.resolve(unsupported,EVIDENCE,8).reason());
        assertEquals(ActorAttribution.Reason.MISSING_PLAYER_UUID,ActorAttribution.resolve(new Node(ActorAttribution.Kind.PLAYER,null,null),EVIDENCE,8).reason());
    }
    @Test public void cyclesAndLongChainsFailWithoutAuthorizing(){
        Node loop=new Node(ActorAttribution.Kind.TNT,null,null);loop.source=loop;
        assertEquals(ActorAttribution.Reason.CYCLE,ActorAttribution.resolve(loop,EVIDENCE,8).reason());
        Node chain=new Node(ActorAttribution.Kind.TNT,null,new Node(ActorAttribution.Kind.PLAYER,UUID.randomUUID(),null));
        assertEquals(ActorAttribution.Reason.DEPTH_LIMIT,ActorAttribution.resolve(chain,EVIDENCE,1).reason());
        try{ActorAttribution.resolve(chain,EVIDENCE,0);fail();}catch(IllegalArgumentException expected){}
    }
}
