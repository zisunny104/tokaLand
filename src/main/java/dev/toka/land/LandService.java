package dev.toka.land;

import dev.toka.land.model.*;
import dev.toka.land.storage.DatasetRepository;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Serial background writes; publish a new immutable index only after durable commit and readback. */
public final class LandService implements AutoCloseable {
    private final DatasetRepository repository;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"tokaLand-writer");t.setDaemon(true);return t;});
    private volatile DatasetRepository.Snapshot snapshot;
    private volatile boolean healthy=true;
    public LandService(DatasetRepository repository)throws IOException{this.repository=repository;snapshot=repository.load();}
    public DatasetRepository.Snapshot snapshot(){return snapshot;}
    public boolean healthy(){return healthy;}
    public static final class ClaimConflict extends RuntimeException {
        public ClaimConflict(String reason){super(reason);}
    }
    public void checkCreation(LandClaim claim){
        for(var existing:snapshot.index().all()){
            if(existing.id().equals(claim.id())){
                if(existing.equals(claim))continue;
                throw new ClaimConflict("ID_CONFLICT");
            }
            if(existing.name().equals(claim.name()))throw new ClaimConflict("NAME_CONFLICT");
            if(existing.kind()==ClaimKind.PLAYER&&claim.kind()==ClaimKind.PLAYER&&overlaps(existing,claim))throw new ClaimConflict("SELECTION_CONFLICT");
        }
    }
    public static boolean overlaps(LandClaim a,LandClaim b){
        for(var x:a.selections())for(var y:b.selections())if(x.world().equals(y.world())
                &&x.minX()<=y.maxX()&&x.maxX()>=y.minX()&&x.minZ()<=y.maxZ()&&x.maxZ()>=y.minZ()
                &&(x.ignoreHeight()||y.ignoreHeight()||x.minY()<=y.maxY()&&x.maxY()>=y.minY()))return true;
        return false;
    }
    /** Native creation, serialized with other writes. Existing claims are never overwritten. */
    public CompletableFuture<String> create(LandClaim claim){
        return CompletableFuture.supplyAsync(()->{
            if(!healthy)throw new IllegalStateException("Land storage requires recovery");
            checkCreation(claim);
            if(snapshot.index().get(claim.id()).filter(claim::equals).isPresent())return snapshot.revision();
            var all=new ArrayList<>(snapshot.index().all());all.add(claim);
            try{snapshot=repository.replace(snapshot.revision(),all);return snapshot.revision();}
            catch(Exception failure){healthy=false;throw new CompletionException(failure);}
        },writer);
    }
    public CompletableFuture<String> replace(String expectedRevision,List<LandClaim> claims){
        List<LandClaim> detached=List.copyOf(claims);
        new LandIndex(detached); // Reject invalid caller input before entering the persistence path.
        return CompletableFuture.supplyAsync(()->{
            if(!healthy)throw new IllegalStateException("Land storage requires recovery");
            try{snapshot=repository.replace(expectedRevision,detached);return snapshot.revision();}
            catch(ConcurrentModificationException error){throw error;}
            catch(Exception error){healthy=false;throw new CompletionException(error);}
        },writer);
    }
    /** Compare the actual edited record, so an unrelated update does not overwrite or invalidate it. */
    public CompletableFuture<String> edit(LandClaim before,LandClaim after){
        if(!before.id().equals(after.id()))throw new IllegalArgumentException("Claim identity cannot change");
        return CompletableFuture.supplyAsync(()->{
            if(!healthy)throw new IllegalStateException("Land storage requires recovery");
            if(!snapshot.index().get(before.id()).filter(before::equals).isPresent())throw new ConcurrentModificationException("CLAIM_CHANGED");
            if(!before.name().equals(after.name())&&snapshot.index().all().stream().anyMatch(c->!c.id().equals(after.id())&&c.name().equals(after.name())))throw new ClaimConflict("NAME_CONFLICT");
            var all=new ArrayList<>(snapshot.index().all());all.removeIf(c->c.id().equals(after.id()));all.add(after);
            new LandIndex(all);
            try{snapshot=repository.replace(snapshot.revision(),all);return snapshot.revision();}
            catch(Exception failure){healthy=false;throw new CompletionException(failure);}
        },writer);
    }
    public CompletableFuture<String> delete(LandClaim expected){
        return CompletableFuture.supplyAsync(()->{
            if(!healthy)throw new IllegalStateException("Land storage requires recovery");
            if(!snapshot.index().get(expected.id()).filter(expected::equals).isPresent())throw new ClaimConflict("CLAIM_CHANGED");
            if(snapshot.index().all().stream().anyMatch(c->expected.id().equals(c.parentClaimId())))throw new ClaimConflict("CHILD_CLAIMS_EXIST");
            var all=new ArrayList<>(snapshot.index().all());all.removeIf(c->c.id().equals(expected.id()));
            try{snapshot=repository.replace(snapshot.revision(),all);return snapshot.revision();}
            catch(Exception failure){healthy=false;throw new CompletionException(failure);}
        },writer);
    }
    @Override public void close()throws IOException{
        writer.shutdown();try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))throw new IOException("Land writes did not finish; store must remain locked until process stops");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    }
}
