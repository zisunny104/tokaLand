package dev.toka.land.model;

import java.util.*;

/** Existing management operations preserve the stable claim/selection IDs and unrelated ACL entries. */
public final class ClaimEdits {
    private ClaimEdits(){}
    private static LandClaim copy(LandClaim c,String name,String type,UUID owner,String ownerName,List<AccessGrant> grants,ClaimDetails details){
        return new LandClaim(c.id(),name,c.kind(),type,owner,c.parentClaimId(),c.inheritOwner(),c.inheritPermissions(),
                c.selections(),grants,c.accessPolicy(),c.localizedNames(),owner==null?c.legacyOwnerName():null,ownerName,details);
    }
    public static LandClaim rename(LandClaim c,String name){
        if(ReservedNames.is(name))
            throw new IllegalArgumentException("RESERVED_NAME");
        return copy(c,name,c.type(),c.ownerId(),c.ownerNameSnapshot(),c.accessGrants(),c.details());
    }
    public static LandClaim owner(LandClaim c,UUID owner,String name){
        return new LandClaim(c.id(),c.name(),c.kind(),c.type(),Objects.requireNonNull(owner),c.parentClaimId(),false,c.inheritPermissions(),
                c.selections(),c.accessGrants(),c.accessPolicy(),c.localizedNames(),null,name,c.details());
    }
    public static LandClaim type(LandClaim c,String type){
        if(!Set.of("land","pvp").contains(type))throw new IllegalArgumentException("INVALID_LAND_TYPE");
        return copy(c,c.name(),type,c.ownerId(),c.ownerNameSnapshot(),c.accessGrants(),c.details());
    }
    public static LandClaim teleport(LandClaim c,ClaimDetails.TeleportPoint point){
        return copy(c,c.name(),c.type(),c.ownerId(),c.ownerNameSnapshot(),c.accessGrants(),c.details().withTeleport(point));
    }
    public static LandClaim member(LandClaim c,AccessSubject subject,boolean add){ return member(c,subject,add?MemberLevel.MEMBER:null); }
    /** Replaces the subject's member grants by the level's; a null level removes the subject. */
    public static LandClaim member(LandClaim c,AccessSubject subject,MemberLevel level){
        if(level!=null&&subject.type()!=AccessSubject.Type.PLAYER_UUID)throw new IllegalArgumentException("MEMBER_IDENTITY_UNCONFIRMED");
        List<AccessGrant> grants=new ArrayList<>(c.accessGrants().stream()
                .filter(g->!g.subject().equals(subject)||!MemberLevel.MANAGED.contains(g.permission())).toList());
        if(level!=null)for(ClaimPermission action:level.permissions())grants.add(new AccessGrant(subject,action,AccessDecision.ALLOW));
        return copy(c,c.name(),c.type(),c.ownerId(),c.ownerNameSnapshot(),grants,c.details());
    }
    public static LandClaim teleportAccess(LandClaim c,TeleportAccess access){
        return copy(c,c.name(),c.type(),c.ownerId(),c.ownerNameSnapshot(),c.accessGrants(),c.details().withTeleportAccess(access));
    }
    public static LandClaim addSelection(LandClaim c,LandSelection part){
        List<LandSelection> parts=new ArrayList<>(c.selections());parts.add(part);
        return new LandClaim(c.id(),c.name(),c.kind(),c.type(),c.ownerId(),c.parentClaimId(),c.inheritOwner(),c.inheritPermissions(),
                parts,c.accessGrants(),c.accessPolicy(),c.localizedNames(),c.legacyOwnerName(),c.ownerNameSnapshot(),c.details());
    }
    /** Replaces the claim's whole shape with one selection; the teleport point must still lie inside it. */
    public static LandClaim reshape(LandClaim c,LandSelection part){
        return new LandClaim(c.id(),c.name(),c.kind(),c.type(),c.ownerId(),c.parentClaimId(),c.inheritOwner(),c.inheritPermissions(),
                List.of(part),c.accessGrants(),c.accessPolicy(),c.localizedNames(),c.legacyOwnerName(),c.ownerNameSnapshot(),c.details());
    }
    /** Same claim in another world: selection IDs are kept and the teleport point moves with it. */
    public static LandClaim inWorld(LandClaim c,String world){
        List<LandSelection> parts=c.selections().stream().map(p->new LandSelection(p.id(),world,p.minX(),p.minY(),p.minZ(),p.maxX(),p.maxY(),p.maxZ(),p.ignoreHeight())).toList();
        var tp=c.details().teleport();
        var details=tp==null?c.details():c.details().withTeleport(new ClaimDetails.TeleportPoint(world,tp.x(),tp.y(),tp.z(),tp.yaw(),tp.pitch()));
        return new LandClaim(c.id(),c.name(),c.kind(),c.type(),c.ownerId(),c.parentClaimId(),c.inheritOwner(),c.inheritPermissions(),
                parts,c.accessGrants(),c.accessPolicy(),c.localizedNames(),c.legacyOwnerName(),c.ownerNameSnapshot(),details);
    }
    public static LandClaim facilityType(LandClaim c,String type){
        return new LandClaim(c.id(),c.name(),c.kind(),type,c.ownerId(),c.parentClaimId(),c.inheritOwner(),c.inheritPermissions(),
                c.selections(),c.accessGrants(),c.accessPolicy(),c.localizedNames(),c.legacyOwnerName(),c.ownerNameSnapshot(),c.details());
    }
    public static LandClaim policy(LandClaim c,AccessPolicy policy){
        return new LandClaim(c.id(),c.name(),c.kind(),c.type(),c.ownerId(),c.parentClaimId(),c.inheritOwner(),c.inheritPermissions(),
                c.selections(),c.accessGrants(),policy,c.localizedNames(),c.legacyOwnerName(),c.ownerNameSnapshot(),c.details());
    }
    /** Sets what deleting the claim pays back; 0 means nothing. */
    public static LandClaim refund(LandClaim c,long value){
        return copy(c,c.name(),c.type(),c.ownerId(),c.ownerNameSnapshot(),c.accessGrants(),c.details().withRefundValue(value));
    }
}
