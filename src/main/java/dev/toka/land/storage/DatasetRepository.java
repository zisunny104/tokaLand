package dev.toka.land.storage;

import com.google.gson.*;
import dev.toka.land.model.*;
import dev.toka.storage.DocumentStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Immutable content documents and a single atomic manifest commit. No old-format import logic. */
public final class DatasetRepository {
    private static final Gson GSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final DocumentStore store;
    public record Snapshot(String revision, LandIndex index){}
    /** Last state this instance read or committed; null forces the next write to reload from storage. */
    private Snapshot current;
    private Map<String,String> currentKeys=Map.of();
    public DatasetRepository(DocumentStore store){this.store=Objects.requireNonNull(store);}

    public synchronized Snapshot load() throws IOException {
        current=null;
        var manifest=store.load("dataset");
        if(manifest.isEmpty()){
            // Native 0.1 prototype records. No zero SQL or legacy plugin format is read here.
            var claims=new LandRepository(store).loadAll();
            current=new Snapshot(claims.isEmpty()?"EMPTY":digest(GSON.toJsonTree(claims)),new LandIndex(claims));
            currentKeys=Map.of();
            return current;
        }
        JsonObject document=manifest.get();
        try{requireSupportedFormat(document.get("format"));}
        catch(IllegalArgumentException e){throw new IOException("Unsupported Land dataset format",e);}
        JsonObject refs=document.getAsJsonObject("claims");
        String revision=digest(refs);
        if(!revision.equals(document.get("revision").getAsString()))throw new IOException("Dataset manifest checksum mismatch");
        List<LandClaim> claims=new ArrayList<>();
        for(var entry:refs.entrySet()){
            String key=entry.getValue().getAsString();
            JsonObject data=store.load(key).orElseThrow(()->new IOException("Missing claim document: "+key));
            LandClaim claim=decode(data);
            if(!entry.getKey().equals(claim.id().toString())||!key.equals(contentKey(claim,data)))throw new IOException("Claim document checksum mismatch");
            claims.add(claim);
        }
        Map<String,String> keys=new HashMap<>();
        refs.entrySet().forEach(entry->keys.put(entry.getKey(),entry.getValue().getAsString()));
        currentKeys=keys;
        return current=new Snapshot(revision,new LandIndex(claims));
    }

    /**
     * Commits a new dataset. Unchanged claims keep their stored document, so a write costs time proportional
     * to the edited claims plus one pass over the rest; only new documents and the manifest are read back.
     */
    public synchronized Snapshot replace(String expectedRevision,Collection<LandClaim> input) throws IOException {
        Snapshot before=current!=null?current:load();
        if(!Objects.equals(expectedRevision,before.revision()))throw new ConcurrentModificationException("Land dataset changed; preview again");
        LandIndex candidate=new LandIndex(input);
        current=null; // Any failure below leaves storage authoritative; the next call reloads it.
        JsonObject refs=new JsonObject();Map<String,JsonObject> writes=new TreeMap<>();
        for(LandClaim claim:candidate.all().stream().sorted(Comparator.comparing(c->c.id().toString())).toList()){
            String id=claim.id().toString();String kept=currentKeys.get(id);
            if(kept!=null&&before.index().get(claim.id()).filter(claim::equals).isPresent()){refs.addProperty(id,kept);continue;}
            JsonObject data=encode(claim);String key=contentKey(claim,data);
            refs.addProperty(id,key);
            Optional<JsonObject> existing=store.load(key);
            if(existing.isEmpty())writes.put(key,data);
            else if(!existing.get().equals(data))throw new IOException("Content-addressed document collision");
        }
        store.saveAll(writes);
        for(var entry:writes.entrySet())if(!store.load(entry.getKey()).orElseThrow().equals(entry.getValue()))throw new IOException("Claim readback mismatch");
        JsonObject manifest=new JsonObject();manifest.addProperty("format",1);manifest.add("claims",refs);manifest.addProperty("revision",digest(refs));
        var old=store.load("dataset");if(old.isPresent())store.save("dataset.previous",old.get());
        store.save("dataset",manifest);
        if(!store.load("dataset").orElseThrow().equals(manifest))throw new IOException("Dataset manifest readback mismatch");
        Map<String,String> keys=new HashMap<>();
        refs.entrySet().forEach(entry->keys.put(entry.getKey(),entry.getValue().getAsString()));
        currentKeys=keys;
        return current=new Snapshot(manifest.get("revision").getAsString(),candidate);
    }

    public static JsonObject encode(LandClaim claim){return GSON.toJsonTree(claim).getAsJsonObject();}
    /** Reads the shared format 1 model and upgrades unversioned prototype records in memory. */
    public static LandClaim decode(JsonObject data){
        if(data.has("accessOverrides")||(data.has("details")&&data.get("details").isJsonObject()
                &&data.getAsJsonObject("details").has("protection")))return decodePrototype(data);
        validateValue(data,LandClaim.class,"claim");
        return Objects.requireNonNull(GSON.fromJson(data,LandClaim.class));
    }
    private static LandClaim decodePrototype(JsonObject data){
        validateValue(data,PrototypeClaim.class,"claim");
        PrototypeClaim old=Objects.requireNonNull(GSON.fromJson(data,PrototypeClaim.class));
        List<AccessGrant> grants=new ArrayList<>();
        if(old.accessOverrides()!=null)old.accessOverrides().forEach((subject,entries)->entries.forEach((permission,decision)->
                grants.add(new AccessGrant(AccessSubject.player(subject),permission,decision))));
        if(old.details()!=null&&old.details().pendingMembers()!=null)for(String name:old.details().pendingMembers())
            for(ClaimPermission permission:ClaimPermission.values())grants.add(new AccessGrant(AccessSubject.legacyName(name),permission,AccessDecision.ALLOW));
        var previous=old.details()==null?null:old.details().protection();
        AccessPolicy.Mode mode=previous==null?AccessPolicy.Mode.PRIVATE:switch(previous){
            case PRIVATE->AccessPolicy.Mode.PRIVATE;
            case OPEN->AccessPolicy.Mode.OPEN;
            case PROTECT,LOCK->AccessPolicy.Mode.PROTECT;
        };
        AccessPolicy policy=new AccessPolicy(mode,previous==PrototypeProtection.LOCK);
        var oldDetails=old.details();
        ClaimDetails details=oldDetails==null?ClaimDetails.privateDefaults():
                new ClaimDetails(oldDetails.creatorId(),oldDetails.creatorName(),oldDetails.teleport(),oldDetails.publicTeleport());
        return new LandClaim(old.id(),old.name(),old.kind(),old.type(),old.ownerId(),old.parentClaimId(),old.inheritOwner(),
                old.inheritPermissions(),old.selections(),grants,policy,old.localizedNames(),old.legacyOwnerName(),
                old.ownerNameSnapshot(),details);
    }
    private enum PrototypeProtection { PRIVATE, OPEN, PROTECT, LOCK }
    private record PrototypeDetails(UUID creatorId,String creatorName,ClaimDetails.TeleportPoint teleport,
                                     PrototypeProtection protection,Set<String> pendingMembers,boolean publicTeleport){}
    private record PrototypeClaim(UUID id,String name,ClaimKind kind,String type,UUID ownerId,UUID parentClaimId,
                                  boolean inheritOwner,boolean inheritPermissions,List<LandSelection> selections,
                                  Map<UUID,Map<ClaimPermission,AccessDecision>> accessOverrides,
                                  Map<String,String> localizedNames,String legacyOwnerName,String ownerNameSnapshot,
                                  PrototypeDetails details){}
    public static void requireSupportedFormat(JsonElement format){
        if(format==null||!format.isJsonPrimitive()||!format.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Dataset format must be integer 1");
        try{if(format.getAsBigDecimal().intValueExact()!=1)throw new IllegalArgumentException("Unsupported dataset format");}
        catch(ArithmeticException e){throw new IllegalArgumentException("Dataset format must be integer 1",e);}
    }
    public static void requireFormatOne(JsonElement format){
        if(format==null||!format.isJsonPrimitive()||!format.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Dataset format must be integer 1");
        try{if(format.getAsBigDecimal().intValueExact()!=1)throw new IllegalArgumentException("Unsupported dataset format");}
        catch(ArithmeticException e){throw new IllegalArgumentException("Dataset format must be integer 1",e);}
    }
    /** Gson otherwise silently drops nested fields and truncates fractional integer coordinates. */
    private static void validateValue(JsonElement value,java.lang.reflect.Type type,String path){
        if(value==null||value.isJsonNull()){
            if(type instanceof Class<?> c&&c.isPrimitive())throw new IllegalArgumentException("Null primitive: "+path);
            return;
        }
        if(type instanceof java.lang.reflect.ParameterizedType p){
            Class<?> raw=(Class<?>)p.getRawType();
            if(Map.class.isAssignableFrom(raw)){
                if(!value.isJsonObject())throw new IllegalArgumentException("Expected object: "+path);
                for(var entry:value.getAsJsonObject().entrySet()){
                    validateValue(new JsonPrimitive(entry.getKey()),p.getActualTypeArguments()[0],path+" key");
                    validateValue(entry.getValue(),p.getActualTypeArguments()[1],path+"."+entry.getKey());
                }
            }else if(Collection.class.isAssignableFrom(raw)){
                if(!value.isJsonArray())throw new IllegalArgumentException("Expected array: "+path);
                int i=0;for(var item:value.getAsJsonArray())validateValue(item,p.getActualTypeArguments()[0],path+"["+(i++)+"]");
            }else throw new IllegalArgumentException("Unsupported schema type: "+type);
            return;
        }
        Class<?> c=(Class<?>)type;
        if(c.isRecord()){
            if(!value.isJsonObject())throw new IllegalArgumentException("Expected record: "+path);
            Map<String,java.lang.reflect.RecordComponent> fields=new HashMap<>();
            for(var field:c.getRecordComponents())fields.put(field.getName(),field);
            for(var entry:value.getAsJsonObject().entrySet()){
                var field=fields.get(entry.getKey());
                if(field==null)throw new IllegalArgumentException("Unsupported field: "+path+"."+entry.getKey());
                validateValue(entry.getValue(),field.getGenericType(),path+"."+field.getName());
            }
            for(var field:fields.values())if(field.getType().isPrimitive()&&!value.getAsJsonObject().has(field.getName()))
                throw new IllegalArgumentException("Missing primitive: "+path+"."+field.getName());
            return;
        }
        if(!value.isJsonPrimitive())throw new IllegalArgumentException("Expected scalar: "+path);
        JsonPrimitive scalar=value.getAsJsonPrimitive();
        if(c==boolean.class){if(!scalar.isBoolean())throw new IllegalArgumentException("Expected boolean: "+path);}
        else if(c==int.class){
            if(!scalar.isNumber())throw new IllegalArgumentException("Expected integer: "+path);
            try{scalar.getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Invalid integer: "+path,e);}
        }else if(c==double.class||c==float.class){
            if(!scalar.isNumber()||!Double.isFinite(scalar.getAsDouble())||(c==float.class&&!Float.isFinite(scalar.getAsFloat())))
                throw new IllegalArgumentException("Invalid number: "+path);
        }else{
            if(!scalar.isString())throw new IllegalArgumentException("Expected string: "+path);
            if(c.isEnum()&&Arrays.stream(c.getEnumConstants()).noneMatch(e->((Enum<?>)e).name().equals(scalar.getAsString())))
                throw new IllegalArgumentException("Unknown enum value: "+path);
            if(c==UUID.class&&!UUID.fromString(scalar.getAsString()).toString().equalsIgnoreCase(scalar.getAsString()))
                throw new IllegalArgumentException("Invalid UUID: "+path);
        }
    }
    private static String contentKey(LandClaim claim,JsonObject data){return "c."+claim.id()+"."+digest(data);}
    public static String digest(JsonElement data){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(data).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private static String canonical(JsonElement value){
        if(value.isJsonObject()){StringJoiner join=new StringJoiner(",","{","}");var object=value.getAsJsonObject();for(String key:new TreeSet<>(object.keySet()))join.add(GSON.toJson(key)+":"+canonical(object.get(key)));return join.toString();}
        if(value.isJsonArray()){StringJoiner join=new StringJoiner(",","[","]");for(var item:value.getAsJsonArray())join.add(canonical(item));return join.toString();}
        return value.toString();
    }
}
