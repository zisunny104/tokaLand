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
            if(overriddenAncestors.contains(claim.id())&&claim.details().protection()==ClaimDetails.ProtectionMode.PRIVATE)continue;
            boolean allowed=switch(claim.details().protection()){
                case LOCK -> actor.administrator()&&actor.lockBypassPermission()&&actor.lockBypassEnabled();
                case OPEN -> true;
                case PROTECT -> actor.administrator()||protectedAuthority.test(claim,action);
                case PRIVATE -> actor.administrator()||ClaimAccess.allows(index,claim.id(),actor.id(),action,false);
            };
            if(!allowed)denied.add(claim.id());
        }
        return new Result(denied.isEmpty(),denied.isEmpty()?"ALLOW":"CLAIM_DENIED",List.copyOf(denied));
    }
}
