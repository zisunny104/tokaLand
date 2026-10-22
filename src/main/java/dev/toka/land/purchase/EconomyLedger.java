package dev.toka.land.purchase;

import cn.nukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Soft dependency on the durable debit/refund contract. Causes and methods are captured on the server thread. */
public final class EconomyLedger implements PurchaseCoordinator.Ledger {
    private final Method debit,refund,credit,status;
    private final Object purchaseCause,refundCause,saleCause;
    private volatile boolean available=true;
    public EconomyLedger(Plugin plugin)throws ReflectiveOperationException{
        ClassLoader loader=plugin.getClass().getClassLoader();
        Class<?> api=Class.forName("dev.toka.economy.Economy",true,loader);
        Class<?> cause=Class.forName("dev.toka.economy.Cause",true,loader);
        status=Class.forName("dev.toka.economy.Result",true,loader).getMethod("status");
        debit=api.getMethod("withdrawOnce",UUID.class,String.class,long.class,cause,String.class);
        credit=api.getMethod("depositOnce",UUID.class,String.class,long.class,cause,String.class);
        refund=api.getMethod("refundWithdrawalOnce",UUID.class,cause,String.class,cause);
        Method of=cause.getMethod("of",String.class,String.class);
        purchaseCause=of.invoke(null,"land-purchase","tokaLand");refundCause=of.invoke(null,"land-compensate","tokaLand");
        saleCause=of.invoke(null,"land-sale","tokaLand");
    }
    public void disconnect(){available=false;}
    @Override public CompletableFuture<String> debit(UUID player,String currency,long amount,String operation){return invoke(debit,player,currency,amount,purchaseCause,operation);}
    @Override public CompletableFuture<String> refund(UUID player,String operation){return invoke(refund,player,purchaseCause,operation,refundCause);}
    @Override public CompletableFuture<String> credit(UUID player,String currency,long amount,String operation){return invoke(credit,player,currency,amount,saleCause,operation);}
    private CompletableFuture<String> invoke(Method method,Object...args){
        if(!available)return CompletableFuture.completedFuture("UNAVAILABLE");
        try{
            Object value=method.invoke(null,args);
            if(!(value instanceof CompletableFuture<?> result))throw new IllegalStateException("Unexpected economy API");
            return result.thenApply(reply->{try{return status.invoke(reply).toString();}catch(ReflectiveOperationException failure){throw new java.util.concurrent.CompletionException(failure);}});
        }catch(ReflectiveOperationException|LinkageError failure){return CompletableFuture.failedFuture(failure);}
    }
}
