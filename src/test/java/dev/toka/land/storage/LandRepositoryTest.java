package dev.toka.land.storage;

import dev.toka.land.model.*;
import dev.toka.storage.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class LandRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void prototypeFallbackCannotBypassStrictNativeDecode() throws Exception {
        var claim=new LandClaim(UUID.randomUUID(),"test",ClaimKind.PLAYER,"land",UUID.randomUUID(),null,false,true,
            List.of(new LandSelection("world",0,0,0,10,10,10,false)),List.of(),AccessPolicy.privateDefault(),Map.of(),null,null,ClaimDetails.privateDefaults());
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1))){
            new LandRepository(store).save(claim);
            var key="claim."+claim.id();var doc=store.load(key).orElseThrow();
            doc.getAsJsonObject("claim").getAsJsonArray("selections").get(0).getAsJsonObject().addProperty("minX",0.75);
            store.save(key,doc);
            try{new DatasetRepository(store).load();fail("fallback must reject lossy coordinates");}catch(IllegalArgumentException expected){}
        }
    }

    @Test public void claimRoundTripsAcrossEverySupportedBackend() throws Exception {
        for (Backend backend : Backend.values()) {
            var directory = temporary.newFolder(backend.name()).toPath();
            UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), member = UUID.randomUUID();
            LandClaim expected = new LandClaim(id, "公用道路", ClaimKind.FACILITY, "road", owner, null,
                    false, true,
                    List.of(new LandSelection("world", -12, 5, -4, 18, 80, 9, true),
                            new LandSelection("world", 19, 5, -4, 20, 80, 9, true)),
                    List.of(new AccessGrant(AccessSubject.player(member),ClaimPermission.BUILD,AccessDecision.DENY)),
                    AccessPolicy.privateDefault(),Map.of("zh-TW","公用道路","en-US","Public road"),null,"Owner snapshot",ClaimDetails.privateDefaults());
            try (DocumentStore store = DocumentStore.open(new StoreConfig(backend, directory, 1))) {
                new LandRepository(store).save(expected);
            }
            try (DocumentStore store = DocumentStore.open(new StoreConfig(backend, directory, 1))) {
                assertEquals(List.of(expected), new LandRepository(store).loadAll());
            }
        }
    }
}
