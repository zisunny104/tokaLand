package dev.toka.land.model;

import java.util.*;

/** Pure policy evaluation; no server APIs, storage, or permission-manager calls on the hot path. */
public final class ProtectionDecision {
    private ProtectionDecision(){}
    public record Actor(UUID id, boolean administrator, boolean lockBypassPermission, boolean lockBypassEnabled) {
        public Actor{Objects.requireNonNull(id);}
    }
    public record Result(boolean allowed, String reason, List<UUID> blockingClaims){}

    public static Result evaluate(LandIndex index, List<LandClaim> hits, Actor actor,
                                  ClaimPermission action, boolean maintenanceLocked) {
        return evaluate(index,hits,actor,action,maintenanceLocked,(claim,permission)->false);
    }
    public static Result evaluate(LandIndex index, List<LandClaim> hits, Actor actor,
                                  ClaimPermission action, boolean maintenanceLocked,
                                  java.util.function.BiPredicate<LandClaim,ClaimPermission> protectedAuthority) {
        return evaluate(index,hits,actor,action,maintenanceLocked,protectedAuthority,
                subject->subject.type()==AccessSubject.Type.PLAYER_UUID&&subject.id().equals(actor.id().toString()));
    }
    public static Result evaluate(LandIndex index, List<LandClaim> hits, Actor actor,
                                  ClaimPermission action, boolean maintenanceLocked,
                                  java.util.function.BiPredicate<LandClaim,ClaimPermission> protectedAuthority,
                                  java.util.function.Predicate<AccessSubject> subjectMatches) {
        if(maintenanceLocked)return new Result(false,"MAINTENANCE_LOCK",List.of());
        // A child resolves its ancestry. Only independent roots combine with deny precedence.
        Set<UUID> overriddenAncestors=new HashSet<>();
        for(var hit:hits){var current=hit;Set<UUID> visited=new HashSet<>();
            while(current.parentClaimId()!=null&&visited.add(current.id())){
                overriddenAncestors.add(current.parentClaimId());current=index.get(current.parentClaimId()).orElseThrow();
            }
        }
        List<UUID> denied=new ArrayList<>();
        for(var claim:hits){
            if(overriddenAncestors.contains(claim.id())&&claim.accessPolicy().mode()==AccessPolicy.Mode.PRIVATE&&!claim.accessPolicy().locked())continue;
            boolean allowed;
            if(claim.accessPolicy().locked())allowed=actor.administrator()&&actor.lockBypassPermission()&&actor.lockBypassEnabled();
            else if(actor.administrator())allowed=true;
            else if(action==ClaimPermission.MANAGE||action==ClaimPermission.CREATE_CHILD)
                allowed=ClaimAccess.allows(index,claim.id(),actor.id(),action,false,subjectMatches);
            else allowed=ClaimAccess.allows(index,claim.id(),actor.id(),action,
                        claim.accessPolicy().mode()==AccessPolicy.Mode.OPEN,subjectMatches)
                    ||claim.accessPolicy().mode()==AccessPolicy.Mode.PROTECT&&protectedAuthority.test(claim,action);
            if(!allowed)denied.add(claim.id());
        }
        return new Result(denied.isEmpty(),denied.isEmpty()?"ALLOW":"CLAIM_DENIED",List.copyOf(denied));
    }
}
