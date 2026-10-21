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
    public DatasetRepository(DocumentStore store){this.store=Objects.requireNonNull(store);}

    public Snapshot load() throws IOException {
        var manifest=store.load("dataset");
        if(manifest.isEmpty()){
            // Native 0.1 prototype records. No zero SQL or legacy plugin format is read here.
            var claims=new LandRepository(store).loadAll();
            return new Snapshot(claims.isEmpty()?"EMPTY":digest(GSON.toJsonTree(claims)),new LandIndex(claims));
        }
        JsonObject document=manifest.get();
        try{requireFormatOne(document.get("format"));}
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
        return new Snapshot(revision,new LandIndex(claims));
    }

    /** Validate and read back every candidate before switching the active manifest. */
    public synchronized Snapshot replace(String expectedRevision,Collection<LandClaim> input) throws IOException {
        Snapshot before=load();
        if(!Objects.equals(expectedRevision,before.revision()))throw new ConcurrentModificationException("Land dataset changed; preview again");
        LandIndex candidate=new LandIndex(input);
        JsonObject refs=new JsonObject();Map<String,JsonObject> writes=new TreeMap<>();
        for(LandClaim claim:candidate.all().stream().sorted(Comparator.comparing(c->c.id().toString())).toList()){
            JsonObject data=encode(claim);String key=contentKey(claim,data);
            refs.addProperty(claim.id().toString(),key);
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
        Snapshot after=load();
        if(!new HashSet<>(after.index().all()).equals(new HashSet<>(candidate.all())))throw new IOException("Dataset readback mismatch");
        return after;
    }

    public static JsonObject encode(LandClaim claim){return GSON.toJsonTree(claim).getAsJsonObject();}
    public static void requireFormatOne(JsonElement format){
        if(format==null||!format.isJsonPrimitive()||!format.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Dataset format must be integer 1");
        try{if(format.getAsBigDecimal().intValueExact()!=1)throw new IllegalArgumentException("Unsupported dataset format");}
        catch(ArithmeticException e){throw new IllegalArgumentException("Dataset format must be integer 1",e);}
    }
    public static LandClaim decode(JsonObject data){
        validateValue(data,LandClaim.class,"claim");
        return Objects.requireNonNull(GSON.fromJson(data,LandClaim.class));
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
