package dev.toka.land.storage;

import com.google.gson.JsonObject;
import dev.toka.land.model.*;
import dev.toka.storage.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class DatasetRepositoryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private LandClaim claim(String name){return new LandClaim(UUID.randomUUID(),name,ClaimKind.PLAYER,"land",UUID.randomUUID(),null,false,true,List.of(new LandSelection("world",0,0,0,5,20,5,false)),List.of(),AccessPolicy.privateDefault(),Map.of(),null,null,ClaimDetails.privateDefaults());}
    @Test public void nestedUnknownFieldsAndLossyPrimitiveValuesAreRejected(){
        for(String field:List.of("minX","minY","minZ","maxX","maxY","maxZ")){
            var data=DatasetRepository.encode(claim("test"));data.getAsJsonArray("selections").get(0).getAsJsonObject().addProperty(field,1.5);
            rejects(data);
        }
        var data=DatasetRepository.encode(claim("test"));data.getAsJsonArray("selections").get(0).getAsJsonObject().addProperty("unknown",true);rejects(data);
        data=DatasetRepository.encode(claim("test"));data.getAsJsonObject("details").addProperty("unknown",true);rejects(data);
        data=DatasetRepository.encode(claim("test"));data.getAsJsonArray("selections").get(0).getAsJsonObject().addProperty("minX",2147483648L);rejects(data);
        data=DatasetRepository.encode(claim("test"));data.addProperty("inheritOwner","false");rejects(data);
        data=DatasetRepository.encode(claim("test"));data.addProperty("kind","UNKNOWN");rejects(data);
        data=DatasetRepository.encode(claim("test"));data.getAsJsonArray("selections").get(0).getAsJsonObject().remove("ignoreHeight");rejects(data);
        assertEquals(claim("unused").kind(),DatasetRepository.decode(DatasetRepository.encode(claim("valid"))).kind());
    }
    private void rejects(JsonObject data){try{DatasetRepository.decode(data);fail("malformed data accepted: "+data);}catch(IllegalArgumentException expected){}}
    @Test public void formatRejectsTruncationOverflowAndStringCoercion(){
        for(String value:List.of("1.5","4294967297","\"1\"","true","null","{}","[]","0","3")){
            try{DatasetRepository.requireFormatOne(com.google.gson.JsonParser.parseString(value));fail(value);}catch(IllegalArgumentException expected){}
        }
        DatasetRepository.requireFormatOne(com.google.gson.JsonParser.parseString("1"));
        try{DatasetRepository.requireSupportedFormat(com.google.gson.JsonParser.parseString("2"));fail("unreleased format number must not be accepted");}catch(IllegalArgumentException expected){}
    }
    @Test public void prototypeFieldsUpgradeToSharedAclAndIndependentLock() {
        var source=claim("old");var old=DatasetRepository.encode(source);
        old.remove("accessGrants");old.remove("accessPolicy");old.getAsJsonObject("details").remove("refundValue");old.getAsJsonObject("details").remove("refundSource");old.getAsJsonObject("details").remove("teleportAccess");
        var overrides=new JsonObject();var member=UUID.randomUUID();var memberActions=new JsonObject();
        memberActions.addProperty("BUILD","ALLOW");overrides.add(member.toString(),memberActions);old.add("accessOverrides",overrides);
        var details=old.getAsJsonObject("details");details.addProperty("protection","LOCK");
        var names=new com.google.gson.JsonArray();names.add("Unresolved");details.add("pendingMembers",names);
        LandClaim upgraded=DatasetRepository.decode(old);
        assertEquals(new AccessPolicy(AccessPolicy.Mode.PROTECT,true),upgraded.accessPolicy());
        assertTrue(upgraded.accessGrants().contains(new AccessGrant(AccessSubject.player(member),ClaimPermission.BUILD,AccessDecision.ALLOW)));
        var legacy=new AccessGrant(AccessSubject.legacyName("Unresolved"),ClaimPermission.BUILD,AccessDecision.ALLOW);
        assertTrue(upgraded.accessGrants().contains(legacy));
        assertFalse(ClaimAccess.allows(new LandIndex(List.of(upgraded)),upgraded.id(),UUID.randomUUID(),ClaimPermission.BUILD,false,s->s.type()==AccessSubject.Type.LEGACY_NAME));
    }
    @Test public void oldManifestLoadsAndKeepsFormatOneOnCommit()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1))){
            var source=claim("old-manifest");var old=DatasetRepository.encode(source);old.remove("accessGrants");old.remove("accessPolicy");old.getAsJsonObject("details").remove("refundValue");old.getAsJsonObject("details").remove("refundSource");old.getAsJsonObject("details").remove("teleportAccess");
            old.getAsJsonObject("details").addProperty("protection","OPEN");old.getAsJsonObject("details").add("pendingMembers",new com.google.gson.JsonArray());
            String key="c."+source.id()+"."+DatasetRepository.digest(old);store.save(key,old);
            var refs=new JsonObject();refs.addProperty(source.id().toString(),key);
            var manifest=new JsonObject();manifest.addProperty("format",1);manifest.add("claims",refs);manifest.addProperty("revision",DatasetRepository.digest(refs));store.save("dataset",manifest);
            var repository=new DatasetRepository(store);var loaded=repository.load();
            assertEquals(AccessPolicy.Mode.OPEN,loaded.index().get(source.id()).orElseThrow().accessPolicy().mode());
            assertEquals(1,store.load("dataset").orElseThrow().get("format").getAsInt());
            var committed=repository.replace(loaded.revision(),loaded.index().all());
            assertTrue(committed.index().get(source.id()).isPresent());
            assertEquals(1,store.load("dataset").orElseThrow().get("format").getAsInt());
        }
    }
    @Test public void oneFieldShapeSupportsPlayerPublicAndFacilityClaims() {
        for(ClaimKind kind:ClaimKind.values()){
            var claim=new LandClaim(UUID.randomUUID(),kind.name(),kind,"shared-type",null,null,false,true,
                    List.of(new LandSelection("world",0,0,0,1,2,3,true)),List.of(),
                    new AccessPolicy(AccessPolicy.Mode.OPEN,false),Map.of(),null,null,ClaimDetails.privateDefaults());
            var encoded=DatasetRepository.encode(claim);var decoded=DatasetRepository.decode(encoded);
            assertEquals(claim,decoded);assertTrue(encoded.has("accessGrants"));assertTrue(encoded.has("accessPolicy"));
        }
    }
    @Test public void teleportCoordinatesCannotSaturateWhenConvertedToBlocks(){
        for(double outside:new double[]{1e100,-1e100,(double)Integer.MAX_VALUE+1,(double)Integer.MIN_VALUE-0.5}){
            try{new ClaimDetails.TeleportPoint("world",outside,0,0,0,0);fail("out of range x");}catch(IllegalArgumentException expected){}
            try{new ClaimDetails.TeleportPoint("world",0,outside,0,0,0);fail("out of range y");}catch(IllegalArgumentException expected){}
            try{new ClaimDetails.TeleportPoint("world",0,0,outside,0,0);fail("out of range z");}catch(IllegalArgumentException expected){}
        }
        new ClaimDetails.TeleportPoint("world",(double)Integer.MAX_VALUE+0.5,Integer.MIN_VALUE,0,0,0);
    }
    @Test public void allBackendsCommitAndReopenOnlyTheActiveManifest()throws Exception{
        for(Backend backend:Backend.values()){
            var path=temporary.newFolder(backend.name()).toPath();LandClaim first=claim("first"),second=claim("second");String revision;
            try(var store=DocumentStore.open(new StoreConfig(backend,path,1))){var repository=new DatasetRepository(store);
                var initial=repository.replace("EMPTY",List.of(first));
                var next=repository.replace(initial.revision(),List.of(second));revision=next.revision();
                assertEquals(List.of(second),List.copyOf(next.index().all()));
                try{repository.replace(initial.revision(),List.of(first));fail("stale revision must be rejected");}catch(ConcurrentModificationException expected){}
            }
            try(var store=DocumentStore.open(new StoreConfig(backend,path,1))){var loaded=new DatasetRepository(store).load();assertEquals(revision,loaded.revision());assertEquals(List.of(second),List.copyOf(loaded.index().all()));}
        }
    }
    @Test public void interruptedManifestCommitDoesNotExposeCandidateRecords()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1))){
            var original=new DatasetRepository(store);LandClaim first=claim("first");var before=original.replace("EMPTY",List.of(first));
            DocumentStore broken=new DocumentStore(){
                public Optional<JsonObject> load(String k)throws IOException{return store.load(k);}
                public void save(String k,JsonObject v)throws IOException{if(k.equals("dataset"))throw new IOException("simulated commit failure");store.save(k,v);}
                public void saveAll(Map<String,JsonObject> v)throws IOException{store.saveAll(v);}
                public void delete(String k)throws IOException{store.delete(k);}
                public Set<String> keys()throws IOException{return store.keys();}
                public SortedMap<String,JsonObject> snapshot()throws IOException{return store.snapshot();}
                public void close(){}
            };
            try{new DatasetRepository(broken).replace(before.revision(),List.of(claim("candidate")));fail();}catch(IOException expected){}
            assertEquals(before.revision(),original.load().revision());assertEquals(List.of(first),List.copyOf(original.load().index().all()));
        }
    }
}
