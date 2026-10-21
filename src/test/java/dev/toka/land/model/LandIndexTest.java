package dev.toka.land.model;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LandIndexTest {
    private static LandClaim claim(UUID id, String name, UUID owner, UUID parent,
                                   boolean inheritOwner, boolean inheritPermissions,
                                   java.util.Map<UUID, java.util.Map<ClaimPermission, AccessDecision>> overrides,
                                   LandSelection... parts) {
        return new LandClaim(id, name, ClaimKind.PLAYER, "land", owner, parent,
                inheritOwner, inheritPermissions, List.of(parts), overrides);
    }
    private static LandClaim claim(ClaimKind kind, String type, LandSelection... parts) {
        return new LandClaim(UUID.randomUUID(), "test", kind, type, UUID.randomUUID(), null,
                false, true, List.of(parts), java.util.Map.of());
    }

    @Test public void multiplePartsOfOneClaimFormAnIrregularShape() {
        LandClaim claim = claim(ClaimKind.PLAYER, "land",
                new LandSelection("world", 0, 0, 0, 2, 10, 2, false),
                new LandSelection("world", 3, 0, 0, 5, 10, 2, false));
        assertTrue(claim.contains("world", 4, 5, 1));
        assertFalse(claim.contains("world", 4, 5, 3));
    }

    @Test public void overlappingClaimsReturnAllHitsAndDeduplicateRepeatedParts() {
        LandClaim player = claim(ClaimKind.PLAYER, "land",
                new LandSelection("world", 0, 0, 0, 4, 10, 4, false),
                new LandSelection("world", 2, 0, 2, 6, 10, 6, false));
        LandClaim road = claim(ClaimKind.FACILITY, "road",
                new LandSelection("world", 3, 0, 0, 3, 20, 8, true));
        List<LandClaim> hits = new LandIndex(List.of(player, road)).findAllAt("world", 3, 5, 3);
        assertEquals(2, hits.size());
        assertTrue(hits.contains(player));
        assertTrue(hits.contains(road));
    }

    @Test public void publicCadastralClaimDoesNotUsePersonalTopbar() {
        assertTrue(claim(ClaimKind.PLAYER, "land", new LandSelection("world",0,0,0,0,0,0,false)).showsPersonalTopbar());
        assertFalse(claim(ClaimKind.PUBLIC, "public", new LandSelection("world",0,0,0,0,0,0,false)).showsPersonalTopbar());
        assertFalse(claim(ClaimKind.FACILITY, "rail", new LandSelection("world",0,0,0,0,0,0,true)).showsPersonalTopbar());
    }

    @Test(expected = IllegalArgumentException.class) public void duplicateClaimIdsAreRejected() {
        LandClaim claim = claim(ClaimKind.PLAYER, "land", new LandSelection("world",0,0,0,0,0,0,false));
        new LandIndex(List.of(claim, claim));
    }

    @Test(expected = IllegalArgumentException.class) public void selectionIdsCannotBeSharedByDifferentClaims() {
        LandSelection selection = new LandSelection("world",0,0,0,4,10,4,false);
        new LandIndex(List.of(claim(ClaimKind.PLAYER,"land",selection), claim(ClaimKind.PUBLIC,"public",selection)));
    }

    @Test public void editingBoundsCanRetainTheSelectionIdentity() {
        LandSelection before = new LandSelection("world",0,0,0,4,10,4,false);
        LandSelection edited = new LandSelection(before.id(),"world",0,0,0,8,10,8,false);
        assertEquals(before.id(),edited.id());
        assertFalse(before.contains("world",7,5,7));
        assertTrue(edited.contains("world",7,5,7));
    }

    @Test public void childInheritsOwnerAndPermissionByReferenceWithLocalOverride() {
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        UUID parentId = UUID.randomUUID(), childId = UUID.randomUUID();
        LandSelection parentArea = new LandSelection("world",0,0,0,10,20,10,false);
        LandSelection childArea = new LandSelection("world",2,0,2,4,20,4,false);
        LandClaim parent = claim(parentId,"parent",owner,null,false,true,
                java.util.Map.of(member,java.util.Map.of(ClaimPermission.BUILD,AccessDecision.ALLOW)),parentArea);
        LandClaim child = claim(childId,"child",null,parentId,true,true,
                java.util.Map.of(member,java.util.Map.of(ClaimPermission.BUILD,AccessDecision.DENY)),childArea);
        LandIndex index = new LandIndex(List.of(parent,child));
        assertEquals(Optional.of(owner),ClaimAccess.effectiveOwner(index,childId));
        assertTrue(ClaimAccess.allows(index,childId,owner,ClaimPermission.MANAGE,false));
        assertFalse(ClaimAccess.allows(index,childId,member,ClaimPermission.BUILD,false));
        assertTrue(ClaimAccess.allows(index,parentId,member,ClaimPermission.BUILD,false));
    }

    @Test public void disabledPermissionInheritanceDoesNotGrantParentMembership() {
        UUID member = UUID.randomUUID(), parentId = UUID.randomUUID(), childId = UUID.randomUUID();
        LandClaim parent = claim(parentId,"parent",UUID.randomUUID(),null,false,true,
                java.util.Map.of(member,java.util.Map.of(ClaimPermission.CONTAINERS,AccessDecision.ALLOW)),
                new LandSelection("world",0,0,0,10,20,10,false));
        LandClaim child = claim(childId,"child",null,parentId,true,false,java.util.Map.of(),
                new LandSelection("world",2,0,2,4,20,4,false));
        assertFalse(ClaimAccess.allows(new LandIndex(List.of(parent,child)),childId,member,ClaimPermission.CONTAINERS,false));
    }

    @Test(expected = IllegalArgumentException.class) public void childOutsideParentIsRejected() {
        UUID parentId = UUID.randomUUID(), childId = UUID.randomUUID();
        LandClaim parent = claim(parentId,"parent",UUID.randomUUID(),null,false,true,Map.of(),
                new LandSelection("world",0,0,0,4,20,4,false));
        LandClaim child = claim(childId,"child",null,parentId,true,true,Map.of(),
                new LandSelection("world",4,0,0,6,20,4,false));
        new LandIndex(List.of(parent,child));
    }

    @Test public void largeClaimsUseFallbackCandidatesWithoutExpandingEveryCell() {
        LandClaim claim = claim(ClaimKind.PUBLIC, "public",
                new LandSelection("world", Integer.MIN_VALUE, 0, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, 0, Integer.MAX_VALUE, true));
        assertEquals(List.of(claim), new LandIndex(List.of(claim)).findAllAt("world", -1, 250, -1));
        assertTrue(new LandIndex(List.of(claim)).findAllAt("other", -1, 250, -1).isEmpty());
    }
    @Test public void childMaySpanAdjacentParentSelectionsIncludingIntegerExtremes(){
        UUID parentId=UUID.randomUUID();
        var parent=claim(parentId,"parent",UUID.randomUUID(),null,false,true,Map.of(),
            new LandSelection("world",Integer.MIN_VALUE,0,0,-1,0,0,true),
            new LandSelection("world",0,0,0,Integer.MAX_VALUE,0,0,true));
        var child=claim(UUID.randomUUID(),"child",null,parentId,true,true,Map.of(),
            new LandSelection("world",Integer.MIN_VALUE,0,0,Integer.MAX_VALUE,0,0,true));
        assertEquals(2,new LandIndex(List.of(parent,child)).all().size());
    }
    @Test public void verticalUnionCoversThreeDimensionalChildButNotUnlimitedChild(){
        UUID parentId=UUID.randomUUID();
        var parent=claim(parentId,"parent",UUID.randomUUID(),null,false,true,Map.of(),
            new LandSelection("world",0,0,0,10,4,10,false),new LandSelection("world",0,5,0,10,9,10,false));
        var child=claim(UUID.randomUUID(),"child",null,parentId,true,true,Map.of(),new LandSelection("world",0,0,0,10,9,10,false));
        new LandIndex(List.of(parent,child));
        var unlimited=claim(UUID.randomUUID(),"unlimited",null,parentId,true,true,Map.of(),new LandSelection("world",0,0,0,10,9,10,true));
        try{new LandIndex(List.of(parent,unlimited));fail("height hole must fail");}catch(IllegalArgumentException expected){}
    }
    @Test public void uncoveredInteriorHoleIsNotHiddenByParentBoundingBox(){
        UUID parentId=UUID.randomUUID();
        var parent=claim(parentId,"parent",UUID.randomUUID(),null,false,true,Map.of(),
            new LandSelection("world",0,0,0,3,9,10,false),new LandSelection("world",5,0,0,10,9,10,false));
        var child=claim(UUID.randomUUID(),"child",null,parentId,true,true,Map.of(),new LandSelection("world",0,0,0,10,9,10,false));
        try{new LandIndex(List.of(parent,child));fail("interior hole must fail");}catch(IllegalArgumentException expected){}
    }
}
