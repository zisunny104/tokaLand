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

/** Durable purchase intent precedes payment. Unknown outcomes always retain the original operation ID. */
public final class PurchaseCoordinator implements AutoCloseable {
    public interface Ledger {
        /** Takes the price from the buyer; {@code beneficiary} receives it, or null when nothing holds the money. */
        CompletableFuture<String> debit(UUID player,UUID beneficiary,String currency,long amount,String operation);
        /** Gives back exactly what {@link #debit} took, from the beneficiary when there was one. */
        CompletableFuture<String> refund(UUID player,UUID beneficiary,String currency,long amount,String operation);
        /** Pays {@code player}; {@code source} pays it, or null when the money is created. */
        default CompletableFuture<String> credit(UUID player,UUID source,String currency,long amount,String operation){return CompletableFuture.failedFuture(new UnsupportedOperationException("Credit API unavailable"));}
        /** Whether the account holds the amount: OK, INSUFFICIENT_FUNDS or another status. */
        default String covers(UUID account,String currency,long amount){return "OK";}
        /** Whether payments can really be moved into this account; if not, a purchase must not record it as the refund source. */
        default boolean accepts(UUID account){return true;}
    }
    public record Outcome(String status,UUID operation,String detail){}
    private enum State { PREPARED, PAID, REFUND_PENDING, COMPLETED, REFUNDED, REJECTED }
    private record Entry(LandClaim claim,String currency,long amount,UUID beneficiary,State state){
        Entry with(State next){return new Entry(claim,currency,amount,beneficiary,next);}
        boolean pending(){return state==State.PREPARED||state==State.PAID||state==State.REFUND_PENDING;}
    }
    private final LandService lands;
    private final DocumentStore store;
    private final Supplier<Ledger> ledger;
    private final Map<UUID,Entry> entries=new LinkedHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"tokaLand-purchases");t.setDaemon(true);return t;});
    private CompletableFuture<List<Outcome>> recovery;
    private volatile int pendingCount;
    private volatile Set<UUID> pendingClaims=Set.of();

    public PurchaseCoordinator(LandService lands,DocumentStore store,Supplier<Ledger> ledger)throws IOException{
        this.lands=lands;this.store=store;this.ledger=ledger;
        for(String key:store.keys())if(key.startsWith("purchase.")){
            JsonObject data=store.load(key).orElseThrow(()->new IOException("Missing purchase journal"));
            try{
                LandClaim claim=DatasetRepository.decode(data.getAsJsonObject("claim"));
                if(claim.ownerId()==null||!key.equals("purchase."+claim.id()))throw new IllegalArgumentException("Journal identity mismatch");
                String currency=data.get("currency").getAsString();long amount=data.get("amount").getAsBigDecimal().longValueExact();
                if(currency.isBlank()||amount<0)throw new IllegalArgumentException("Invalid purchase quote");
                UUID beneficiary=data.has("beneficiary")?UUID.fromString(data.get("beneficiary").getAsString()):null;
                entries.put(claim.id(),new Entry(claim,currency,amount,beneficiary,State.valueOf(data.get("state").getAsString())));
            }catch(RuntimeException malformed){throw new IOException("Invalid purchase journal: "+key,malformed);}
        }
        publishPending();
    }
    public boolean hasPending(){return pendingCount>0;}
    public boolean hasPending(UUID claim){return pendingClaims.contains(claim);}
    private void publishPending(){
        pendingClaims=entries.values().stream().filter(Entry::pending).map(e->e.claim.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        pendingCount=pendingClaims.size();
    }
    /** @param beneficiary the account the price is paid into, or null to take the money out of the economy */
    public CompletableFuture<Outcome> purchase(LandClaim claim,String currency,long amount,UUID beneficiary){
        Objects.requireNonNull(claim);Objects.requireNonNull(currency);
        if(claim.ownerId()==null||currency.isBlank()||amount<0)throw new IllegalArgumentException("Invalid purchase intent");
        return CompletableFuture.supplyAsync(()->{
            try{
                Entry known=entries.get(claim.id());
                if(known!=null){
                    if(!known.claim.equals(claim)||!known.currency.equals(currency)||known.amount!=amount||!Objects.equals(known.beneficiary,beneficiary))return result("CONFLICT",known,"OPERATION_MISMATCH");
                    return advance(known);
                }
                for(Entry pending:entries.values())if(pending.pending()&&(
                        pending.claim.ownerId().equals(claim.ownerId())||pending.claim.name().equals(claim.name())||LandService.overlaps(pending.claim,claim)))
                    return result("PENDING",pending,"EXISTING_PURCHASE");
                lands.checkCreation(claim);
                Entry intent=new Entry(claim,currency,amount,beneficiary,State.PREPARED);save(intent);
                return advance(intent);
            }catch(LandService.ClaimConflict conflict){return new Outcome("REJECTED",claim.id(),conflict.getMessage());}
            catch(Exception failure){return new Outcome("PENDING",claim.id(),root(failure).getClass().getSimpleName());}
        },worker);
    }
    public synchronized CompletableFuture<List<Outcome>> recover(){
        if(recovery!=null&&!recovery.isDone())return recovery;
        recovery=CompletableFuture.supplyAsync(()->{
            List<Outcome> results=new ArrayList<>();
            for(Entry entry:new ArrayList<>(entries.values()))if(entry.pending())results.add(advance(entry));
            return List.copyOf(results);
        },worker);
        return recovery;
    }
    private Outcome advance(Entry entry){
        try{
            if(!entry.pending())return result(entry.state.name(),entry,"");
            var existing=lands.snapshot().index().get(entry.claim.id());
            if(existing.isPresent()){
                if(!existing.get().equals(entry.claim))return result("PENDING",entry,"CLAIM_ID_CONFLICT");
                if(entry.state==State.PREPARED&&entry.amount>0)return result("PENDING",entry,"PAYMENT_STATE_UNCONFIRMED");
                if(entry.state==State.REFUND_PENDING)return result("PENDING",entry,"CLAIM_AND_REFUND_CONFLICT");
                save(entry.with(State.COMPLETED));return result("COMPLETED",entry,"");
            }
            Ledger payments=ledger.get();
            if(entry.amount>0&&payments==null)return result("PENDING",entry,"ECONOMY_UNAVAILABLE");
            save(entry); // A prior failed journal acknowledgement must be made durable before continuing.
            if(entry.state==State.PREPARED){
                String payment=entry.amount==0?"OK":payments.debit(entry.claim.ownerId(),entry.beneficiary,entry.currency,entry.amount,entry.claim.id().toString()).get(30,TimeUnit.SECONDS);
                if(!"OK".equals(payment)){
                    if(!Set.of("INSUFFICIENT_FUNDS","NO_ACCOUNT","UNKNOWN_CURRENCY").contains(payment))return result("PENDING",entry,payment);
                    save(entry.with(State.REJECTED));return result("REJECTED",entry,payment);
                }
                entry=entry.with(State.PAID);save(entry);
            }
            if(entry.state==State.PAID){
                try{lands.create(entry.claim).get(30,TimeUnit.SECONDS);}
                catch(Exception failure){
                    if(!(root(failure) instanceof LandService.ClaimConflict))throw failure;
                    entry=entry.with(State.REFUND_PENDING);save(entry);
                }
                if(entry.state==State.PAID){save(entry.with(State.COMPLETED));return result("COMPLETED",entry,"");}
            }
            if(entry.state==State.REFUND_PENDING){
                String refunded=entry.amount==0?"OK":payments.refund(entry.claim.ownerId(),entry.beneficiary,entry.currency,entry.amount,entry.claim.id().toString()).get(30,TimeUnit.SECONDS);
                if(!"OK".equals(refunded))return result("PENDING",entry,"REFUND_"+refunded);
                save(entry.with(State.REFUNDED));return result("REFUNDED",entry,"CREATION_CONFLICT");
            }
            return result("PENDING",entry,"UNKNOWN_STATE");
        }catch(Exception failure){return result("PENDING",entry,root(failure).getClass().getSimpleName());}
    }
    private void save(Entry entry)throws IOException{
        JsonObject data=new JsonObject();data.add("claim",DatasetRepository.encode(entry.claim));data.addProperty("currency",entry.currency);
        data.addProperty("amount",entry.amount);data.addProperty("state",entry.state.name());String key="purchase."+entry.claim.id();
        if(entry.beneficiary!=null)data.addProperty("beneficiary",entry.beneficiary.toString());
        // Keep the intent reserved even if persistence succeeds but its acknowledgement is lost.
        Entry previous=entries.put(entry.claim.id(),entry);
        publishPending();
        try{
            store.save(key,data);
            if(!store.load(key).orElseThrow().equals(data))throw new IOException("Purchase journal readback mismatch");
        }catch(IOException failure){
            // A terminal state is not published in memory until its acknowledgement is confirmed.
            if(previous!=null)entries.put(entry.claim.id(),previous);
            publishPending();throw failure;
        }
    }
    private static Outcome result(String status,Entry entry,String detail){return new Outcome(status,entry.claim.id(),detail);}
    private static Throwable root(Throwable failure){while((failure instanceof ExecutionException||failure instanceof CompletionException)&&failure.getCause()!=null)failure=failure.getCause();return failure;}
    @Override public void close()throws IOException{
        worker.shutdown();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))throw new IOException("Purchase writes still pending");}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException(stopped);}
    }
}
