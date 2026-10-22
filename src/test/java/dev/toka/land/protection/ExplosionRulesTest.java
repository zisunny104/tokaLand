package dev.toka.land.protection;

import dev.toka.land.model.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ExplosionRulesTest {
    private LandClaim claim(String world,int min,int max,boolean flat){
        return new LandClaim(UUID.randomUUID(),"land",ClaimKind.PLAYER,"land",UUID.randomUUID(),null,false,false,
            List.of(new LandSelection(world,min,50,0,max,70,10,flat)),Map.of());
    }
    private final ActorAttribution.Result unknown=new ActorAttribution.Result(null,ActorAttribution.Reason.MISSING_SOURCE,1);
    @Test public void overlapIsNotLimitedToTheExplosionCenterAndReturnsEveryClaim(){
        LandClaim first=claim("w",0,10,false),second=claim("w",9,20,false),elsewhere=claim("other",0,20,false);
        var decision=ExplosionRules.parse("cancel-whole",true,32).evaluate(new LandIndex(List.of(first,second,elsewhere)),
            new ExplosionRules.Bounds("w",8,60,0,12,62,2),unknown,c->true);
        assertTrue(decision.cancel());assertEquals(Set.of(first.id(),second.id()),new HashSet<>(decision.deniedClaims()));
    }
    @Test public void confirmedPlayerMustPassEveryAffectedClaimEvenWhenEnvironmentIsOff(){
        LandClaim first=claim("w",0,10,false),second=claim("w",10,20,false);
        var player=new ActorAttribution.Result(UUID.randomUUID(),ActorAttribution.Reason.CONFIRMED_PLAYER,2);
        var decision=ExplosionRules.parse("cancel-whole",false,32).evaluate(new LandIndex(List.of(first,second)),
            new ExplosionRules.Bounds("w",9,60,0,11,60,2),player,c->c.id().equals(first.id()));
        assertTrue(decision.cancel());assertEquals(List.of(second.id()),decision.deniedClaims());
    }
    @Test public void unknownSourceUsesEnvironmentPolicyAndDoesNotInvokePlayerAuthority(){
        LandClaim land=claim("w",0,10,false);var index=new LandIndex(List.of(land));var bounds=new ExplosionRules.Bounds("w",0,60,0,1,61,1);
        assertTrue(ExplosionRules.parse("cancel-whole",true,32).evaluate(index,bounds,unknown,c->{throw new AssertionError("unknown player");}).cancel());
        assertFalse(ExplosionRules.parse("cancel-whole",false,32).evaluate(index,bounds,unknown,c->false).cancel());
        assertFalse(ExplosionRules.parse("disabled",true,32).evaluate(index,bounds,unknown,c->false).cancel());
    }
    @Test public void heightAndWorldBoundariesAreRespectedWithoutIntegerOverflow(){
        assertFalse(new ExplosionRules.Bounds("w",0,100,0,1,101,1).intersects(claim("w",0,10,false).selections().getFirst()));
        assertTrue(new ExplosionRules.Bounds("w",0,100,0,1,101,1).intersects(claim("w",0,10,true).selections().getFirst()));
        assertFalse(new ExplosionRules.Bounds("w",11,60,0,12,60,1).intersects(claim("w",0,10,false).selections().getFirst()));
        assertTrue(new ExplosionRules.Bounds("w",Integer.MAX_VALUE,60,0,(long)Integer.MAX_VALUE+1,60,1).intersects(claim("w",Integer.MAX_VALUE,Integer.MAX_VALUE,false).selections().getFirst()));
        try{ExplosionRules.parse("protect-inside",true,32);fail("unimplemented mode accepted");}catch(IllegalArgumentException expected){}
    }
}
