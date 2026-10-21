package dev.toka.land.model;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtectionDecisionTest {
    private final UUID owner=UUID.randomUUID();
    private LandClaim claim(ClaimDetails.ProtectionMode mode,UUID parent){return new LandClaim(UUID.randomUUID(),"claim",mode==ClaimDetails.ProtectionMode.PRIVATE?ClaimKind.PLAYER:ClaimKind.FACILITY,"test",owner,parent,false,true,List.of(new LandSelection("world",0,0,0,10,20,10,false)),Map.of(),Map.of(),null,null,new ClaimDetails(null,null,null,mode,Set.of(),false));}
    private ProtectionDecision.Result run(List<LandClaim> claims,ProtectionDecision.Actor actor,boolean locked){var index=new LandIndex(claims);return ProtectionDecision.evaluate(index,index.findAllAt("world",5,5,5),actor,ClaimPermission.BUILD,locked);}
    @Test public void roadDenialWinsOverPlayerOwnership(){assertFalse(run(List.of(claim(ClaimDetails.ProtectionMode.PRIVATE,null),claim(ClaimDetails.ProtectionMode.PROTECT,null)),new ProtectionDecision.Actor(owner,false,false,false),false).allowed());}
    @Test public void administratorCanAssistOwnerButMustExplicitlyEnableLockBypass(){
        var admin=UUID.randomUUID();assertTrue(run(List.of(claim(ClaimDetails.ProtectionMode.PRIVATE,null)),new ProtectionDecision.Actor(admin,true,false,false),false).allowed());
        var locked=List.of(claim(ClaimDetails.ProtectionMode.LOCK,null));
        assertFalse(run(locked,new ProtectionDecision.Actor(admin,true,true,false),false).allowed());
        assertFalse(run(locked,new ProtectionDecision.Actor(admin,true,false,true),false).allowed());
        assertTrue(run(locked,new ProtectionDecision.Actor(admin,true,true,true),false).allowed());
    }
    @Test public void maintenanceLockAlwaysWins(){assertFalse(run(List.of(),new ProtectionDecision.Actor(owner,true,true,true),true).allowed());}
    @Test public void childCannotOverrideLockedParent(){var parent=claim(ClaimDetails.ProtectionMode.LOCK,null);var child=claim(ClaimDetails.ProtectionMode.PRIVATE,parent.id());assertFalse(run(List.of(parent,child),new ProtectionDecision.Actor(owner,false,false,false),false).allowed());}
    @Test public void protectedAuthorityIsSeparateFromOwnershipAndOnlyGrantsItsOwnClaim(){
        var protectedClaim=claim(ClaimDetails.ProtectionMode.PROTECT,null);var index=new LandIndex(List.of(protectedClaim));
        var actor=new ProtectionDecision.Actor(owner,false,false,false);
        assertFalse(ProtectionDecision.evaluate(index,List.of(protectedClaim),actor,ClaimPermission.BUILD,false).allowed());
        assertTrue(ProtectionDecision.evaluate(index,List.of(protectedClaim),actor,ClaimPermission.BUILD,false,(c,p)->c.id().equals(protectedClaim.id())).allowed());
        var locked=claim(ClaimDetails.ProtectionMode.LOCK,null);index=new LandIndex(List.of(protectedClaim,locked));
        var result=ProtectionDecision.evaluate(index,index.findAllAt("world",5,5,5),actor,ClaimPermission.BUILD,false,(c,p)->true);
        assertFalse(result.allowed());assertEquals(List.of(locked.id()),result.blockingClaims());
    }
}
