package dev.toka.land.model;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Temporary elevation only after a caller-supplied durable audit confirms its write. */
public final class ProtectionSessions {
    public static final long MAX_DURATION_MILLIS=30*60*1000L;
    private record Session(UUID token,long expires){}
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Map<UUID,UUID> requests=new HashMap<>();
    private final Clock clock;
    private final Function<String,CompletableFuture<Void>> confirmedAudit;
    public ProtectionSessions(Clock clock,Function<String,CompletableFuture<Void>> confirmedAudit){
        this.clock=Objects.requireNonNull(clock);this.confirmedAudit=Objects.requireNonNull(confirmedAudit);
    }
    public CompletableFuture<Boolean> enable(UUID player,boolean administrator,boolean permission,long durationMillis){
        Objects.requireNonNull(player);
        if(!administrator||!permission)return CompletableFuture.completedFuture(false);
        if(durationMillis<=0||durationMillis>MAX_DURATION_MILLIS)throw new IllegalArgumentException("Invalid bypass duration");
        long expiry=Math.addExact(clock.millis(),durationMillis);UUID token=UUID.randomUUID();
        synchronized(this){requests.put(player,token);sessions.remove(player);}
        CompletableFuture<Void> recorded;
        try{recorded=Objects.requireNonNull(confirmedAudit.apply("LOCK_BYPASS_ENABLE player="+player+" token="+token+" expires="+expiry));}
        catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        return recorded.thenApply(ignored->{synchronized(this){
            if(!token.equals(requests.get(player))||clock.millis()>=expiry)return false;
            sessions.put(player,new Session(token,expiry));return true;
        }});
    }
    /** Revocation is immediate, including pending requests, even when auditing is unavailable. */
    public synchronized void revoke(UUID player){requests.remove(player);sessions.remove(player);}
    public synchronized void clear(){requests.clear();sessions.clear();}
    public synchronized boolean enabled(UUID player,boolean administrator,boolean permission){
        Session session=sessions.get(player);
        if(session==null)return false;
        if(!administrator||!permission||clock.millis()>=session.expires()){revoke(player);return false;}
        return true;
    }
}
