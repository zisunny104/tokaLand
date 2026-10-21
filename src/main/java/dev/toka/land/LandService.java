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
    public CompletableFuture<String> update(String expectedRevision,LandClaim claim){
        var current=snapshot;if(!current.revision().equals(expectedRevision))return CompletableFuture.failedFuture(new ConcurrentModificationException("Land dataset changed"));
        List<LandClaim> all=new ArrayList<>(current.index().all());all.removeIf(c->c.id().equals(claim.id()));all.add(claim);
        return replace(expectedRevision,all);
    }
    @Override public void close()throws IOException{
        writer.shutdown();try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))throw new IOException("Land writes did not finish; store must remain locked until process stops");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    }
}
