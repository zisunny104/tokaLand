package dev.toka.land.purchase;

import dev.toka.land.*;
import dev.toka.land.model.*;
import dev.toka.land.storage.DatasetRepository;
import dev.toka.storage.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PurchaseCoordinatorTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private LandClaim claim(String name,int x){
        UUID owner=UUID.randomUUID();return new LandClaim(UUID.randomUUID(),name,ClaimKind.PLAYER,"land",owner,null,false,false,
                List.of(new LandSelection("world",x,0,0,x+3,20,3,false)),List.of(),AccessPolicy.privateDefault(),Map.of(),null,"Owner",
                new ClaimDetails(owner,"Owner",new ClaimDetails.TeleportPoint("world",x+1,2,1,0,0),true));
    }
    private static final class Ledger implements PurchaseCoordinator.Ledger {
        final Set<String> debits=new HashSet<>(),refunds=new HashSet<>();long spent;boolean loseAcknowledgement;Runnable afterDebit=()->{};
        public CompletableFuture<String> debit(UUID player,String currency,long amount,String operation){
            if(debits.add(operation)){spent+=amount;afterDebit.run();}
            if(loseAcknowledgement){loseAcknowledgement=false;return CompletableFuture.failedFuture(new IOException("lost acknowledgement"));}
            return CompletableFuture.completedFuture("OK");
        }
        public CompletableFuture<String> refund(UUID player,String operation){if(refunds.add(operation))spent=0;return CompletableFuture.completedFuture("OK");}
    }
    private static final class FaultStore implements DocumentStore {
        final DocumentStore delegate;boolean failIntent,loseDatasetAck;
        FaultStore(DocumentStore delegate){this.delegate=delegate;}
        public Optional<com.google.gson.JsonObject> load(String key)throws IOException{return delegate.load(key);}
        public void save(String key,com.google.gson.JsonObject data)throws IOException{
            if(failIntent&&key.startsWith("purchase.")){failIntent=false;throw new IOException("intent write rejected");}
            delegate.save(key,data);
            if(loseDatasetAck&&key.equals("dataset")){loseDatasetAck=false;throw new IOException("lost dataset acknowledgement");}
        }
        public void saveAll(Map<String,com.google.gson.JsonObject> values)throws IOException{delegate.saveAll(values);}
        public void delete(String key)throws IOException{delegate.delete(key);}
        public Set<String> keys()throws IOException{return delegate.keys();}
        public SortedMap<String,com.google.gson.JsonObject> snapshot()throws IOException{return delegate.snapshot();}
        public void close(){}
    }
    @Test public void failedIntentWriteCannotDebitUntilTheJournalIsConfirmed()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store))){
            var fault=new FaultStore(store);fault.failIntent=true;var ledger=new Ledger();
            try(var purchases=new PurchaseCoordinator(lands,fault,()->ledger)){
                assertEquals("PENDING",purchases.purchase(claim("Intent",0),"money",60).get().status());
                assertEquals(0,ledger.spent);assertTrue(purchases.hasPending());
                assertEquals("COMPLETED",purchases.recover().get().getFirst().status());
                assertEquals(60,ledger.spent);assertFalse(purchases.hasPending());
            }
        }
    }
    @Test public void uncertainLandCommitIsNotRefundedAndRecoversByReadback()throws Exception{
        var folder=temporary.newFolder().toPath();var ledger=new Ledger();var claim=claim("Uncertain",0);
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,folder,1))){
            var fault=new FaultStore(store);fault.loseDatasetAck=true;
            try(var lands=new LandService(new DatasetRepository(fault));var purchases=new PurchaseCoordinator(lands,fault,()->ledger)){
                assertEquals("PENDING",purchases.purchase(claim,"money",80).get().status());
                assertFalse(lands.healthy());assertEquals(80,ledger.spent);assertTrue(ledger.refunds.isEmpty());
            }
        }
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,folder,1));var lands=new LandService(new DatasetRepository(store));var purchases=new PurchaseCoordinator(lands,store,()->ledger)){
            assertEquals("COMPLETED",purchases.recover().get().getFirst().status());
            assertEquals(80,ledger.spent);assertEquals(1,ledger.debits.size());assertTrue(ledger.refunds.isEmpty());
            assertEquals(claim,lands.snapshot().index().get(claim.id()).orElseThrow());
        }
    }
    @Test public void repeatedOperationDebitsOnceAndPublishesOneClaim()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store))){
            var ledger=new Ledger();var claim=claim("Home",0);
            try(var purchases=new PurchaseCoordinator(lands,store,()->ledger)){
                assertEquals("COMPLETED",purchases.purchase(claim,"money",123).get().status());
                assertEquals("COMPLETED",purchases.purchase(claim,"money",123).get().status());
                assertEquals(123,ledger.spent);assertEquals(1,lands.snapshot().index().all().size());
                assertEquals("CONFLICT",purchases.purchase(claim,"money",124).get().status());
                assertEquals(123,ledger.spent);
            }
        }
    }
    @Test public void lostDebitAcknowledgementRecoversWithTheSameReceipt()throws Exception{
        var folder=temporary.newFolder().toPath();var ledger=new Ledger();ledger.loseAcknowledgement=true;var claim=claim("Recover",0);
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,folder,1));var lands=new LandService(new DatasetRepository(store));var purchases=new PurchaseCoordinator(lands,store,()->ledger)){
            assertEquals("PENDING",purchases.purchase(claim,"money",90).get().status());assertEquals(0,lands.snapshot().index().all().size());
            assertEquals(90,ledger.spent);
        }
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,folder,1));var lands=new LandService(new DatasetRepository(store));var purchases=new PurchaseCoordinator(lands,store,()->ledger)){
            assertEquals("COMPLETED",purchases.recover().get().getFirst().status());assertEquals(90,ledger.spent);
            assertEquals(1,ledger.debits.size());assertTrue(lands.snapshot().index().get(claim.id()).isPresent());
        }
    }
    @Test public void definiteCreationConflictAfterPaymentRefundsOnlyOnce()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store))){
            var ledger=new Ledger();var candidate=claim("Candidate",0);var other=claim("Concurrent",0);ledger.afterDebit=()->lands.create(other).join();
            try(var purchases=new PurchaseCoordinator(lands,store,()->ledger)){
                assertEquals("REFUNDED",purchases.purchase(candidate,"money",75).get().status());
                assertEquals("REFUNDED",purchases.purchase(candidate,"money",75).get().status());
                assertEquals(0,ledger.spent);assertEquals(1,ledger.refunds.size());assertFalse(lands.snapshot().index().get(candidate.id()).isPresent());
            }
        }
    }
    @Test public void existingConflictDoesNotCreateAnIntentOrDebit()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store))){
            lands.create(claim("Existing",0)).get();var ledger=new Ledger();
            try(var purchases=new PurchaseCoordinator(lands,store,()->ledger)){
                assertEquals("REJECTED",purchases.purchase(claim("Overlap",0),"money",50).get().status());
                assertEquals(0,ledger.spent);assertTrue(store.keys().stream().noneMatch(k->k.startsWith("purchase.")));
            }
        }
    }
    @Test public void missingEconomyKeepsIntentAndReservesTheSameNameAndArea()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store));var purchases=new PurchaseCoordinator(lands,store,()->null)){
            var first=claim("Pending",0);assertEquals("PENDING",purchases.purchase(first,"money",5).get().status());
            var second=claim("Other",0);var result=purchases.purchase(second,"money",5).get();
            assertEquals("PENDING",result.status());assertEquals(first.id(),result.operation());
            assertEquals(1,store.keys().stream().filter(k->k.startsWith("purchase.")).count());
        }
    }
    @Test public void approvedZeroPriceDoesNotCallTheLedger()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store));var purchases=new PurchaseCoordinator(lands,store,()->null)){
            assertEquals("COMPLETED",purchases.purchase(claim("Exempt",0),"money",0).get().status());assertEquals(1,lands.snapshot().index().all().size());
        }
    }
}
