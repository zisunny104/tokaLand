package dev.toka.land.model;

import org.junit.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ProtectionSessionsTest {
    private static final class Time extends Clock {
        long now=1000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}public long millis(){return now;}
    }
    @Test public void elevationWaitsForConfirmedAuditAndExpires(){
        Time time=new Time();var saved=new CompletableFuture<Void>();List<String> lines=new ArrayList<>();
        var sessions=new ProtectionSessions(time,line->{lines.add(line);return saved;});UUID player=UUID.randomUUID();
        var enabled=sessions.enable(player,true,true,500);
        assertFalse(enabled.isDone());assertFalse(sessions.enabled(player,true,true));assertEquals(1,lines.size());
        saved.complete(null);assertTrue(enabled.join());assertTrue(sessions.enabled(player,true,true));
        time.now=1500;assertFalse(sessions.enabled(player,true,true));
    }
    @Test public void failedAuditAndRevocationCannotActivatePendingRequest(){
        var failed=new ProtectionSessions(new Time(),line->CompletableFuture.failedFuture(new java.io.IOException("audit disk")));
        UUID player=UUID.randomUUID();assertTrue(failed.enable(player,true,true,500).isCompletedExceptionally());assertFalse(failed.enabled(player,true,true));
        var gate=new CompletableFuture<Void>();var pending=new ProtectionSessions(new Time(),line->gate);
        var result=pending.enable(player,true,true,500);pending.revoke(player);gate.complete(null);
        assertFalse(result.join());assertFalse(pending.enabled(player,true,true));
    }
    @Test public void bothPrivilegesAreRequiredAndRevocationDoesNotResurrect(){
        var sessions=new ProtectionSessions(new Time(),line->CompletableFuture.completedFuture(null));UUID player=UUID.randomUUID();
        assertFalse(sessions.enable(player,false,true,500).join());assertFalse(sessions.enable(player,true,false,500).join());
        assertTrue(sessions.enable(player,true,true,500).join());assertFalse(sessions.enabled(player,true,false));
        assertFalse(sessions.enabled(player,true,true));
    }
}
