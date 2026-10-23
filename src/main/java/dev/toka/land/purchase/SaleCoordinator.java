package dev.toka.land.purchase;

import com.google.gson.*;
import dev.toka.land.LandService;
import dev.toka.land.model.LandClaim;
import dev.toka.land.storage.DatasetRepository;
import dev.toka.storage.DocumentStore;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Original claim and fixed sale refund survive deletion, lost acknowledgements and restarts. */
public final class SaleCoordinator implements AutoCloseable {
    private enum State { PREPARED, REMOVED, COMPLETED, REJECTED }
    private record Entry(LandClaim claim,UUID recipient,String currency,long amount,UUID source,State state){
        Entry with(State next){return new Entry(claim,recipient,currency,amount,source,next);}
        boolean pending(){return state==State.PREPARED||state==State.REMOVED;}
    }
    private final LandService lands;private final DocumentStore store;private final Supplier<PurchaseCoordinator.Ledger> ledger;
    private final Map<UUID,Entry> entries=new LinkedHashMap<>();
    private volatile Set<UUID> pending=Set.of();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"tokaLand-sales");t.setDaemon(true);return t;});
    private CompletableFuture<List<PurchaseCoordinator.Outcome>> recovery;
    public SaleCoordinator(LandService lands,DocumentStore store,Supplier<PurchaseCoordinator.Ledger> ledger)throws IOException{
        this.lands=lands;this.store=store;this.ledger=ledger;
        for(String key:store.keys())if(key.startsWith("sale."))try{
            JsonObject data=store.load(key).orElseThrow();LandClaim claim=DatasetRepository.decode(data.getAsJsonObject("claim"));
            if(!key.equals("sale."+claim.id()))throw new IllegalArgumentException("Sale identity mismatch");
            UUID recipient=UUID.fromString(data.get("recipient").getAsString());String currency=data.get("currency").getAsString();
            long amount=data.get("amount").getAsBigDecimal().longValueExact();if(amount<0||currency.isBlank())throw new IllegalArgumentException("Invalid refund quote");
            UUID source=data.has("source")?UUID.fromString(data.get("source").getAsString()):null;
            entries.put(claim.id(),new Entry(claim,recipient,currency,amount,source,State.valueOf(data.get("state").getAsString())));
        }catch(RuntimeException invalid){throw new IOException("Invalid sale journal: "+key,invalid);}
        publish();
    }
    public boolean hasPending(){return !pending.isEmpty();}
    public boolean hasPending(UUID claim){return pending.contains(claim);}
    /** @param source the account that pays the refund, or null when the refund is created */
    public CompletableFuture<PurchaseCoordinator.Outcome> sell(LandClaim claim,UUID recipient,String currency,long amount,UUID source){
        Objects.requireNonNull(recipient);if(amount<0||currency==null||currency.isBlank())throw new IllegalArgumentException("Invalid refund quote");
        return CompletableFuture.supplyAsync(()->{
            try{
                Entry known=entries.get(claim.id());
                // A refused sale changed nothing, so it is not a sale in progress: the next attempt starts afresh.
                if(known!=null&&known.state==State.REJECTED)known=null;
                if(known!=null){
                    if(!known.claim.equals(claim)||!known.recipient.equals(recipient)||!known.currency.equals(currency)||known.amount!=amount||!Objects.equals(known.source,source))return result("CONFLICT",known,"SALE_MISMATCH");
                    return advance(known);
                }
                if(!lands.snapshot().index().get(claim.id()).filter(claim::equals).isPresent())return new PurchaseCoordinator.Outcome("REJECTED",claim.id(),"CLAIM_CHANGED");
                var entry=new Entry(claim,recipient,currency,amount,source,State.PREPARED);save(entry);return advance(entry);
            }catch(Exception failure){return new PurchaseCoordinator.Outcome("PENDING",claim.id(),root(failure).getClass().getSimpleName());}
        },worker);
    }
    public synchronized CompletableFuture<List<PurchaseCoordinator.Outcome>> recover(){
        if(recovery!=null&&!recovery.isDone())return recovery;
        recovery=CompletableFuture.supplyAsync(()->{List<PurchaseCoordinator.Outcome> outcomes=new ArrayList<>();for(Entry e:new ArrayList<>(entries.values()))if(e.pending())outcomes.add(advance(e));return List.copyOf(outcomes);},worker);
        return recovery;
    }
    private PurchaseCoordinator.Outcome advance(Entry entry){
        try{
            if(!entry.pending())return result(entry.state.name(),entry,"");
            var money=ledger.get();if(entry.amount>0&&money==null)return result("PENDING",entry,"ECONOMY_UNAVAILABLE");
            save(entry);
            if(entry.state==State.PREPARED){
                var current=lands.snapshot().index().get(entry.claim.id());
                if(current.isPresent()){
                    if(!current.get().equals(entry.claim)){save(entry.with(State.REJECTED));return result("REJECTED",entry,"CLAIM_CHANGED");}
                    try{lands.delete(entry.claim).get(30,TimeUnit.SECONDS);}
                    catch(Exception failure){
                        if(root(failure) instanceof LandService.ClaimConflict conflict){save(entry.with(State.REJECTED));return result("REJECTED",entry,conflict.getMessage());}
                        throw failure;
                    }
                }
                entry=entry.with(State.REMOVED);save(entry);
            }
            if(lands.snapshot().index().get(entry.claim.id()).isPresent())return result("PENDING",entry,"REMOVED_CLAIM_REAPPEARED");
            String status=entry.amount==0?"OK":money.credit(entry.recipient,entry.source,entry.currency,entry.amount,"sale:"+entry.claim.id()).get(30,TimeUnit.SECONDS);
            if(!status.equals("OK"))return result("PENDING",entry,"REFUND_"+status);
            save(entry.with(State.COMPLETED));return result("COMPLETED",entry,"");
        }catch(Exception failure){return result("PENDING",entry,root(failure).getClass().getSimpleName());}
    }
    private void publish(){pending=entries.values().stream().filter(Entry::pending).map(e->e.claim.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    private void save(Entry entry)throws IOException{
        var data=new JsonObject();data.add("claim",DatasetRepository.encode(entry.claim));data.addProperty("recipient",entry.recipient.toString());
        data.addProperty("currency",entry.currency);data.addProperty("amount",entry.amount);data.addProperty("state",entry.state.name());
        if(entry.source!=null)data.addProperty("source",entry.source.toString());
        Entry previous=entries.put(entry.claim.id(),entry);publish();
        try{String key="sale."+entry.claim.id();store.save(key,data);if(!store.load(key).orElseThrow().equals(data))throw new IOException("Sale readback mismatch");}
        catch(IOException failure){if(previous!=null)entries.put(entry.claim.id(),previous);publish();throw failure;}
    }
    private static PurchaseCoordinator.Outcome result(String status,Entry entry,String detail){return new PurchaseCoordinator.Outcome(status,entry.claim.id(),detail);}
    private static Throwable root(Throwable failure){while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();return failure;}
    @Override public void close()throws IOException{worker.shutdown();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))throw new IOException("Sale writes still pending");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}}
}
