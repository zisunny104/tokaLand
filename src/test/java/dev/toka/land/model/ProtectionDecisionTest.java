package dev.toka.land.model;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtectionDecisionTest {
    private final UUID owner=UUID.randomUUID();
    private LandClaim claim(AccessPolicy policy,UUID claimOwner,UUID parent){return new LandClaim(UUID.randomUUID(),"claim",claimOwner==null?ClaimKind.FACILITY:ClaimKind.PLAYER,"test",claimOwner,parent,false,true,List.of(new LandSelection("world",0,0,0,10,20,10,false)),List.of(),policy,Map.of(),null,null,ClaimDetails.privateDefaults());}
    private ProtectionDecision.Result run(List<LandClaim> claims,ProtectionDecision.Actor actor,boolean locked){var index=new LandIndex(claims);return ProtectionDecision.evaluate(index,index.findAllAt("world",5,5,5),actor,ClaimPermission.BUILD,locked);}
    @Test public void overlappingProtectFacilityDenialWinsOverPlayerOwnership(){assertFalse(run(List.of(claim(new AccessPolicy(AccessPolicy.Mode.PRIVATE,false),owner,null),claim(new AccessPolicy(AccessPolicy.Mode.PROTECT,false),null,null)),new ProtectionDecision.Actor(owner,false,false,false),false).allowed());}
    @Test public void administratorCanAssistOwnerButMustExplicitlyEnableLockBypass(){
        var admin=UUID.randomUUID();assertTrue(run(List.of(claim(AccessPolicy.privateDefault(),owner,null)),new ProtectionDecision.Actor(admin,true,false,false),false).allowed());
        var locked=List.of(claim(new AccessPolicy(AccessPolicy.Mode.PROTECT,true),owner,null));
        assertFalse(run(locked,new ProtectionDecision.Actor(admin,true,false,false),false).allowed());
        assertFalse(run(locked,new ProtectionDecision.Actor(admin,true,true,false),false).allowed());
        assertFalse(run(locked,new ProtectionDecision.Actor(admin,true,false,true),false).allowed());
        assertTrue(run(locked,new ProtectionDecision.Actor(admin,true,true,true),false).allowed());
    }
    @Test public void maintenanceLockAlwaysWins(){assertFalse(run(List.of(),new ProtectionDecision.Actor(owner,true,true,true),true).allowed());}
    @Test public void childCannotOverrideLockedParent(){var parent=claim(new AccessPolicy(AccessPolicy.Mode.PROTECT,true),null,null);var child=claim(AccessPolicy.privateDefault(),null,parent.id());assertFalse(run(List.of(parent,child),new ProtectionDecision.Actor(owner,false,false,false),false).allowed());}
    @Test public void protectedAuthorityIsSeparateFromOwnershipAndOnlyGrantsItsOwnClaim(){
        var protectedClaim=claim(new AccessPolicy(AccessPolicy.Mode.PROTECT,false),null,null);var index=new LandIndex(List.of(protectedClaim));
        var actor=new ProtectionDecision.Actor(owner,false,false,false);
        assertFalse(ProtectionDecision.evaluate(index,List.of(protectedClaim),actor,ClaimPermission.BUILD,false).allowed());
        assertTrue(ProtectionDecision.evaluate(index,List.of(protectedClaim),actor,ClaimPermission.BUILD,false,(c,p)->c.id().equals(protectedClaim.id())).allowed());
        var locked=claim(new AccessPolicy(AccessPolicy.Mode.PROTECT,true),null,null);index=new LandIndex(List.of(protectedClaim,locked));
        var result=ProtectionDecision.evaluate(index,index.findAllAt("world",5,5,5),actor,ClaimPermission.BUILD,false,(c,p)->true);
        assertFalse(result.allowed());assertEquals(List.of(locked.id()),result.blockingClaims());
    }
    @Test public void typedGroupsResolveThroughProviderAndLegacyNamesNeverAuthorize(){
        var group=AccessSubject.group("luckperms","builder");
        var grant=new AccessGrant(group,ClaimPermission.BUILD,AccessDecision.ALLOW);
        var claim=new LandClaim(UUID.randomUUID(),"group land",ClaimKind.PLAYER,"land",owner,null,false,true,
                List.of(new LandSelection("world",0,0,0,10,20,10,false)),List.of(grant),AccessPolicy.privateDefault(),Map.of(),null,null,ClaimDetails.privateDefaults());
        var index=new LandIndex(List.of(claim));var member=UUID.randomUUID();
        assertFalse(ClaimAccess.allows(index,claim.id(),member,ClaimPermission.BUILD,false));
        assertTrue(ClaimAccess.allows(index,claim.id(),member,ClaimPermission.BUILD,false,s->s.equals(group)));
        var legacy=new LandClaim(UUID.randomUUID(),"legacy",ClaimKind.PLAYER,"land",owner,null,false,true,
                List.of(new LandSelection("world",0,0,0,10,20,10,false)),List.of(new AccessGrant(AccessSubject.legacyName("Alex"),ClaimPermission.BUILD,AccessDecision.ALLOW)),AccessPolicy.privateDefault(),Map.of(),null,null,ClaimDetails.privateDefaults());
        assertFalse(ClaimAccess.allows(new LandIndex(List.of(legacy)),legacy.id(),member,ClaimPermission.BUILD,false,s->true));
    }
}
