package dev.toka.land.protection;

import java.util.*;

/** Resolves evidence only. A confirmed UUID is not itself an authorization decision. */
public final class ActorAttribution {
    private ActorAttribution(){}
    public enum Kind { PLAYER, TNT, PROJECTILE, UNKNOWN }
    public enum Reason { CONFIRMED_PLAYER, MISSING_SOURCE, MISSING_PLAYER_UUID, UNSUPPORTED_SOURCE, CYCLE, DEPTH_LIMIT }
    public record Result(UUID playerId,Reason reason,int inspected) {
        public boolean confirmed(){return playerId!=null&&reason==Reason.CONFIRMED_PLAYER;}
    }
    public interface Evidence<T> {
        Kind kind(T node);
        UUID playerId(T node);
        T source(T node);
    }
    /** Only explicit TNT source / projectile shooter edges may be followed; never proximity or placement history. */
    public static <T> Result resolve(T origin,Evidence<T> evidence,int maxDepth) {
        Objects.requireNonNull(evidence);
        if(maxDepth<1||maxDepth>1024)throw new IllegalArgumentException("maxDepth must be between 1 and 1024");
        Set<T> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        T current=origin;
        int inspected=0;
        while(current!=null){
            if(!visited.add(current))return new Result(null,Reason.CYCLE,inspected);
            if(inspected>=maxDepth)return new Result(null,Reason.DEPTH_LIMIT,inspected);
            inspected++;
            Kind kind=Objects.requireNonNull(evidence.kind(current));
            if(kind==Kind.PLAYER){
                UUID player=evidence.playerId(current);
                return new Result(player,player==null?Reason.MISSING_PLAYER_UUID:Reason.CONFIRMED_PLAYER,inspected);
            }
            if(kind!=Kind.TNT&&kind!=Kind.PROJECTILE)return new Result(null,Reason.UNSUPPORTED_SOURCE,inspected);
            current=evidence.source(current);
        }
        return new Result(null,Reason.MISSING_SOURCE,inspected);
    }
}
