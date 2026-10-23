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
    /**
     * After a failed write the copy in memory is in doubt, so the stored data is read back on the writer thread; if it is
     * readable and consistent it becomes the current data and Land carries on. Completes with whether Land is healthy.
     */
    public CompletableFuture<Boolean> recover(){
        if(healthy)return CompletableFuture.completedFuture(true);
        return CompletableFuture.supplyAsync(()->{
            try{ensureHealthy();return true;}
            catch(RuntimeException stillBroken){return false;}
        },writer);
    }
    private void ensureHealthy(){
        if(healthy)return;
        try{snapshot=repository.load();healthy=true;}
        catch(Exception failure){throw new IllegalStateException("Land storage requires recovery",failure);}
    }
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
            if(existing.kind()==ClaimKind.PLAYER&&claim.kind()==ClaimKind.PLAYER&&overlaps(existing,claim)&&!related(snapshot.index(),existing,claim))throw new ClaimConflict("SELECTION_CONFLICT");
        }
    }
    /** A sub-claim lies inside its parents by design, so claims on the same parent chain never conflict. */
    private static boolean related(LandIndex index,LandClaim a,LandClaim b){
        return isAncestor(index,a.id(),b)||isAncestor(index,b.id(),a);
    }
    private static boolean isAncestor(LandIndex index,UUID ancestor,LandClaim of){
        Set<UUID> seen=new HashSet<>();
        for(UUID parent=of.parentClaimId();parent!=null&&seen.add(parent);parent=index.get(parent).map(LandClaim::parentClaimId).orElse(null))
            if(parent.equals(ancestor))return true;
        return false;
    }
    public static boolean overlaps(LandSelection x,LandSelection y){
        return x.world().equals(y.world())&&x.minX()<=y.maxX()&&x.maxX()>=y.minX()&&x.minZ()<=y.maxZ()&&x.maxZ()>=y.minZ()
                &&(x.ignoreHeight()||y.ignoreHeight()||x.minY()<=y.maxY()&&x.maxY()>=y.minY());
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
            ensureHealthy();
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
            ensureHealthy();
            try{snapshot=repository.replace(expectedRevision,detached);return snapshot.revision();}
            catch(ConcurrentModificationException error){throw error;}
            catch(Exception error){healthy=false;throw new CompletionException(error);}
        },writer);
    }
    /** Compare the actual edited record, so an unrelated update does not overwrite or invalidate it. */
    public CompletableFuture<String> edit(LandClaim before,LandClaim after){
        if(!before.id().equals(after.id()))throw new IllegalArgumentException("Claim identity cannot change");
        return CompletableFuture.supplyAsync(()->{
            ensureHealthy();
            if(!snapshot.index().get(before.id()).filter(before::equals).isPresent())throw new ConcurrentModificationException("CLAIM_CHANGED");
            if(!before.name().equals(after.name())&&snapshot.index().all().stream().anyMatch(c->!c.id().equals(after.id())&&c.name().equals(after.name())))throw new ClaimConflict("NAME_CONFLICT");
            if(after.kind()==ClaimKind.PLAYER&&!before.selections().equals(after.selections())
                &&snapshot.index().all().stream().anyMatch(c->!c.id().equals(after.id())&&c.kind()==ClaimKind.PLAYER&&overlaps(c,after)&&!related(snapshot.index(),c,after)))
                throw new ClaimConflict("SELECTION_CONFLICT");
            var all=new ArrayList<>(snapshot.index().all());all.removeIf(c->c.id().equals(after.id()));all.add(after);
            new LandIndex(all);
            try{snapshot=repository.replace(snapshot.revision(),all);return snapshot.revision();}
            catch(Exception failure){healthy=false;throw new CompletionException(failure);}
        },writer);
    }
    public CompletableFuture<String> delete(LandClaim expected){
        return CompletableFuture.supplyAsync(()->{
            ensureHealthy();
            if(!snapshot.index().get(expected.id()).filter(expected::equals).isPresent())throw new ClaimConflict("CLAIM_CHANGED");
            if(snapshot.index().all().stream().anyMatch(c->expected.id().equals(c.parentClaimId())))throw new ClaimConflict("CHILD_CLAIMS_EXIST");
            var all=new ArrayList<>(snapshot.index().all());all.removeIf(c->c.id().equals(expected.id()));
            try{snapshot=repository.replace(snapshot.revision(),all);return snapshot.revision();}
            catch(Exception failure){healthy=false;throw new CompletionException(failure);}
        },writer);
    }
    /** Points every claim of one world at a new world name in one commit; refuses if the new name already holds claims. Completes with the number renamed. */
    public CompletableFuture<Integer> renameWorld(String from,String to){
        if(from==null||to==null||from.isBlank()||to.isBlank()||from.equals(to))throw new IllegalArgumentException("INVALID_WORLD_NAME");
        return CompletableFuture.supplyAsync(()->{
            ensureHealthy();
            var current=snapshot.index().all();
            if(current.stream().anyMatch(c->c.world().equals(to)))throw new ClaimConflict("WORLD_NAME_IN_USE");
            var all=new ArrayList<LandClaim>();int renamed=0;
            for(LandClaim claim:current){
                if(claim.world().equals(from)){all.add(ClaimEdits.inWorld(claim,to));renamed++;}
                else all.add(claim);
            }
            if(renamed==0)return 0;
            try{snapshot=repository.replace(snapshot.revision(),all);return renamed;}
            catch(Exception failure){healthy=false;throw new CompletionException(failure);}
        },writer);
    }
    /** Removes all given claims in one commit, only if each is unchanged and nothing outside the set still has them as parent. */
    public CompletableFuture<String> deleteAll(List<LandClaim> expected){
        List<LandClaim> targets=List.copyOf(expected);
        return CompletableFuture.supplyAsync(()->{
            ensureHealthy();
            Set<UUID> ids=new HashSet<>();
            for(LandClaim claim:targets){
                if(!snapshot.index().get(claim.id()).filter(claim::equals).isPresent())throw new ClaimConflict("CLAIM_CHANGED");
                ids.add(claim.id());
            }
            if(snapshot.index().all().stream().anyMatch(c->!ids.contains(c.id())&&c.parentClaimId()!=null&&ids.contains(c.parentClaimId())))
                throw new ClaimConflict("CHILD_CLAIMS_EXIST");
            var all=new ArrayList<>(snapshot.index().all());all.removeIf(c->ids.contains(c.id()));
            try{snapshot=repository.replace(snapshot.revision(),all);return snapshot.revision();}
            catch(Exception failure){healthy=false;throw new CompletionException(failure);}
        },writer);
    }
    @Override public void close()throws IOException{
        writer.shutdown();try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))throw new IOException("Land writes did not finish; store must remain locked until process stops");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    }
}
