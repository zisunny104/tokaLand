package dev.toka.land.protection;

import dev.toka.land.model.*;
import java.util.*;
import java.util.function.Predicate;

/** Server-wide blast policy. Bounds must cover block changes AND entity damage/knockback. */
public record ExplosionRules(Mode mode,boolean environmentProtection,int attributionMaxDepth) {
    public enum Mode { CANCEL_WHOLE, DISABLED }
    public record Bounds(String world,double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public Bounds {
            if(world==null||world.isBlank()||!Double.isFinite(minX)||!Double.isFinite(minY)||!Double.isFinite(minZ)
                ||!Double.isFinite(maxX)||!Double.isFinite(maxY)||!Double.isFinite(maxZ)
                ||minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("Invalid blast bounds");
        }
        public boolean intersects(LandSelection part){
            return world.equals(part.world())&&minX<(long)part.maxX()+1&&maxX>=part.minX()
                &&minZ<(long)part.maxZ()+1&&maxZ>=part.minZ()
                &&(part.ignoreHeight()||(minY<(long)part.maxY()+1&&maxY>=part.minY()));
        }
    }
    public record Decision(boolean cancel,String reason,List<UUID> affectedClaims,List<UUID> deniedClaims){}
    public ExplosionRules {
        Objects.requireNonNull(mode);
        if(attributionMaxDepth<1||attributionMaxDepth>1024)throw new IllegalArgumentException("Invalid attribution-max-depth");
    }
    public static ExplosionRules parse(String mode,boolean environmentProtection,int maxDepth){
        return new ExplosionRules(Mode.valueOf(Objects.requireNonNull(mode).strip().replace('-','_').toUpperCase(Locale.ROOT)),environmentProtection,maxDepth);
    }
    /** Unknown attribution uses environment policy. A UUID is never treated as an automatic grant. */
    public Decision evaluate(LandIndex index,Bounds bounds,ActorAttribution.Result actor,Predicate<LandClaim> playerAuthorized){
        Objects.requireNonNull(index);Objects.requireNonNull(bounds);Objects.requireNonNull(actor);Objects.requireNonNull(playerAuthorized);
        if(mode==Mode.DISABLED)return new Decision(false,"DISABLED",List.of(),List.of());
        List<LandClaim> hits=index.all().stream().filter(c->c.selections().stream().anyMatch(bounds::intersects))
            .sorted(Comparator.comparing(c->c.id().toString())).toList();
        // A child hit replaces the private protection of its ancestors for this operation.
        // Other overlapping claims still participate independently.
        Set<UUID> overriddenAncestors=new HashSet<>();
        for(LandClaim hit:hits){LandClaim current=hit;Set<UUID> visited=new HashSet<>();
            while(current.parentClaimId()!=null&&visited.add(current.id())){
                overriddenAncestors.add(current.parentClaimId());
                current=index.get(current.parentClaimId()).orElseThrow();
            }
        }
        List<LandClaim> effectiveHits=hits.stream().filter(c->!(overriddenAncestors.contains(c.id())
            &&c.details().protection()==ClaimDetails.ProtectionMode.PRIVATE)).toList();
        List<UUID> denied=effectiveHits.stream().filter(c->actor.confirmed()?!playerAuthorized.test(c):environmentProtection).map(LandClaim::id).toList();
        return new Decision(!denied.isEmpty(),denied.isEmpty()?"ALLOW":actor.confirmed()?"PLAYER_DENIED":"ENVIRONMENT_PROTECTED",hits.stream().map(LandClaim::id).toList(),denied);
    }
}
