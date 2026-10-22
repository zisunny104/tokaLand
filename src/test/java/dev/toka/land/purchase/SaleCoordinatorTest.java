package dev.toka.land.purchase;

import dev.toka.land.*;
import dev.toka.land.model.*;
import dev.toka.land.storage.DatasetRepository;
import dev.toka.storage.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SaleCoordinatorTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private LandClaim claim(){
        UUID owner=UUID.randomUUID();return new LandClaim(UUID.randomUUID(),"Home",ClaimKind.PLAYER,"land",owner,null,false,true,
                List.of(new LandSelection("world",0,0,0,10,20,10,false)),List.of(),AccessPolicy.privateDefault(),Map.of(),null,"Owner",new ClaimDetails(owner,"Owner",null,true));
    }
    private static final class Ledger implements PurchaseCoordinator.Ledger {
        final Set<String> receipts=new HashSet<>();long credited;boolean loseReply;
        public CompletableFuture<String> debit(UUID player,String currency,long amount,String op){throw new AssertionError("A sale must not debit");}
        public CompletableFuture<String> refund(UUID player,String op){throw new AssertionError("Sale refund is not failed-purchase compensation");}
        public CompletableFuture<String> credit(UUID player,String currency,long amount,String op){
            assertTrue(op.startsWith("sale:"));if(receipts.add(op))credited+=amount;
            if(loseReply){loseReply=false;return CompletableFuture.failedFuture(new IOException("lost credit acknowledgement"));}
            return CompletableFuture.completedFuture("OK");
        }
    }
    @Test public void deletionAndRefundAreDurableAndIdempotentAcrossBackends()throws Exception{
        for(Backend backend:Backend.values())try(var store=DocumentStore.open(new StoreConfig(backend,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store))){
            var c=claim();lands.create(c).get();var ledger=new Ledger();
            try(var sales=new SaleCoordinator(lands,store,()->ledger)){
                assertEquals("COMPLETED",sales.sell(c,c.ownerId(),"money",70).get().status());
                assertEquals("COMPLETED",sales.sell(c,c.ownerId(),"money",70).get().status());
                assertEquals(70,ledger.credited);assertEquals(1,ledger.receipts.size());assertTrue(lands.snapshot().index().all().isEmpty());
                assertEquals("CONFLICT",sales.sell(c,c.ownerId(),"money",71).get().status());assertEquals(70,ledger.credited);
            }
        }
    }
    @Test public void lostRefundReplyIsRecoveredAfterRestartWithoutCreditingTwice()throws Exception{
        var folder=temporary.newFolder().toPath();var c=claim();var ledger=new Ledger();ledger.loseReply=true;
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,folder,1));var lands=new LandService(new DatasetRepository(store));var sales=new SaleCoordinator(lands,store,()->ledger)){
            lands.create(c).get();assertEquals("PENDING",sales.sell(c,c.ownerId(),"money",99).get().status());
            assertTrue(sales.hasPending(c.id()));assertTrue(lands.snapshot().index().all().isEmpty());assertEquals(99,ledger.credited);
            assertEquals(c,DatasetRepository.decode(store.load("sale."+c.id()).orElseThrow().getAsJsonObject("claim")));
        }
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,folder,1));var lands=new LandService(new DatasetRepository(store));var sales=new SaleCoordinator(lands,store,()->ledger)){
            assertEquals("COMPLETED",sales.recover().get().getFirst().status());assertFalse(sales.hasPending());
            assertEquals(99,ledger.credited);assertEquals(1,ledger.receipts.size());assertTrue(lands.snapshot().index().all().isEmpty());
        }
    }
    @Test public void missingEconomyReservesSaleWithoutDeletingLand()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store));var sales=new SaleCoordinator(lands,store,()->null)){
            var c=claim();lands.create(c).get();assertEquals("PENDING",sales.sell(c,c.ownerId(),"money",40).get().status());
            assertTrue(lands.snapshot().index().get(c.id()).isPresent());assertTrue(sales.hasPending(c.id()));
        }
    }
    @Test public void parentCannotBeDeletedWhileItsChildWouldBeOrphaned()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store))){
            var parent=claim();var child=new LandClaim(UUID.randomUUID(),"Child",ClaimKind.PLAYER,"land",null,parent.id(),true,true,
                List.of(new LandSelection("world",1,1,1,2,2,2,false)),List.of(),AccessPolicy.privateDefault(),Map.of(),null,null,ClaimDetails.privateDefaults());
            lands.replace(lands.snapshot().revision(),List.of(parent,child)).get();var ledger=new Ledger();
            try(var sales=new SaleCoordinator(lands,store,()->ledger)){
                var result=sales.sell(parent,parent.ownerId(),"money",30).get();assertEquals("REJECTED",result.status());assertEquals("CHILD_CLAIMS_EXIST",result.detail());
                assertEquals(2,lands.snapshot().index().all().size());assertEquals(0,ledger.credited);assertTrue(lands.healthy());
            }
        }
    }
    @Test public void zeroRefundDeletesWithoutCallingMoneyApis()throws Exception{
        try(var store=DocumentStore.open(new StoreConfig(Backend.JSON,temporary.newFolder().toPath(),1));var lands=new LandService(new DatasetRepository(store));var sales=new SaleCoordinator(lands,store,()->null)){
            var c=claim();lands.create(c).get();assertEquals("COMPLETED",sales.sell(c,c.ownerId(),"money",0).get().status());assertTrue(lands.snapshot().index().all().isEmpty());
        }
    }
}
