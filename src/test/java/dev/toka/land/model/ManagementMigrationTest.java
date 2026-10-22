package dev.toka.land.model;

import dev.toka.land.LandService;
import dev.toka.land.storage.DatasetRepository;
import dev.toka.storage.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ManagementMigrationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private LandClaim claim(String name,int x){
        UUID owner=UUID.randomUUID();return new LandClaim(UUID.randomUUID(),name,ClaimKind.PLAYER,"land",owner,null,false,true,
                List.of(new LandSelection("world",x,0,0,x+2,10,2,false)),List.of(),AccessPolicy.privateDefault(),Map.of("zh-TW","名稱"),null,"Original",
                new ClaimDetails(owner,"Original",new ClaimDetails.TeleportPoint("world",x+1,1,1,20,30),true));
    }
    @Test public void renameAndTransferKeepStableGeometryCreatorAndUnrelatedFields(){
        var original=claim("Before",0);var renamed=ClaimEdits.rename(original,"After");
        assertEquals(original.id(),renamed.id());assertEquals(original.selections(),renamed.selections());assertEquals(original.details(),renamed.details());
        assertEquals(original.ownerId(),renamed.ownerId());assertEquals(original.localizedNames(),renamed.localizedNames());
        UUID next=UUID.randomUUID();var transferred=ClaimEdits.owner(renamed,next,"Next");
        assertEquals(next,transferred.ownerId());assertEquals(original.details(),transferred.details());assertEquals(original.selections(),transferred.selections());
        assertFalse(transferred.inheritOwner());assertTrue(transferred.inheritPermissions());
    }
    @Test public void sharingIsIdempotentAndDoesNotGrantManagement(){
        var original=claim("Shared",0);UUID member=UUID.randomUUID();var subject=AccessSubject.player(member);
        var added=ClaimEdits.member(original,subject,true);assertEquals(3,added.accessGrants().size());
        assertEquals(added,ClaimEdits.member(added,subject,true));
        var index=new LandIndex(List.of(added));
        assertTrue(ClaimAccess.allows(index,added.id(),member,ClaimPermission.BUILD,false));
        assertFalse(ClaimAccess.allows(index,added.id(),member,ClaimPermission.MANAGE,false));
        assertFalse(ClaimAccess.allows(index,added.id(),member,ClaimPermission.CREATE_CHILD,false));
        assertEquals(original,ClaimEdits.member(added,subject,false));
    }
    @Test public void removingPendingLegacyShareDoesNotTouchConfirmedMembers(){
        var c=claim("Pending",0);UUID member=UUID.randomUUID();var confirmed=ClaimEdits.member(c,AccessSubject.player(member),true);
        var grants=new ArrayList<>(confirmed.accessGrants());var legacy=AccessSubject.legacyName("OldName");
        grants.add(new AccessGrant(legacy,ClaimPermission.BUILD,AccessDecision.ALLOW));
        var pending=new LandClaim(c.id(),c.name(),c.kind(),c.type(),c.ownerId(),c.parentClaimId(),c.inheritOwner(),c.inheritPermissions(),c.selections(),grants,c.accessPolicy(),c.localizedNames(),null,c.ownerNameSnapshot(),c.details());
        assertEquals(confirmed,ClaimEdits.member(pending,legacy,false));
        try{ClaimEdits.member(c,legacy,true);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void multipleHitsRequireExplicitTargetAndDuplicateNamesRemainAmbiguous(){
        var a=claim("Same",0);var b=claim("Same",0);var hits=List.of(a,b);
        for(String query:Arrays.asList(null,"same"))try{ClaimTargets.resolve(hits,query);fail();}catch(ClaimTargets.Ambiguous expected){assertEquals(2,expected.matches().size());}
        assertEquals(b,ClaimTargets.resolve(hits,b.id().toString()));
        assertEquals(a,ClaimTargets.resolve(List.of(a),null));
    }
    @Test public void staleEditCannotOverwriteOwnerButUnrelatedEditsCanProceed()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var service=new LandService(new DatasetRepository(store))){
            var a=claim("A",0);var b=claim("B",10);service.create(a).get();service.create(b).get();
            var transferred=ClaimEdits.owner(a,UUID.randomUUID(),"Next");service.edit(a,transferred).get();
            try{service.edit(a,ClaimEdits.rename(a,"Stale")).get();fail();}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof ConcurrentModificationException);}
            service.edit(b,ClaimEdits.rename(b,"B2")).get();assertTrue(service.healthy());
            assertEquals(transferred,service.snapshot().index().get(a.id()).orElseThrow());
            assertEquals("B2",service.snapshot().index().get(b.id()).orElseThrow().name());
            try{service.edit(transferred,ClaimEdits.rename(transferred,"B2")).get();fail();}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof LandService.ClaimConflict);}
            assertTrue(service.healthy());
        }
    }
    @Test public void teleportEditRejectsOutsidePoints(){
        try{ClaimEdits.teleport(claim("Home",0),new ClaimDetails.TeleportPoint("world",100,1,1,0,0));fail();}catch(IllegalArgumentException expected){}
    }
}
