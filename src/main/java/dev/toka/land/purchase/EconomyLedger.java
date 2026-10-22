package dev.toka.land.purchase;

import cn.nukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Soft dependency on tokaEconomy's durable payments. With a treasury account, money moves in one fee-free transfer;
 * without one the price leaves the economy and refunds are created, as before.
 */
public final class EconomyLedger implements PurchaseCoordinator.Ledger {
    private final Method withdraw,refundWithdrawal,deposit,status;
    /** Treasury support is optional: an older tokaEconomy simply has none of these, and payments then behave as before. */
    private final Method transfer,has,hasAccount;
    private final Object purchaseCause,compensateCause,saleCause;
    private volatile boolean available=true;
    public EconomyLedger(Plugin plugin)throws ReflectiveOperationException{
        ClassLoader loader=plugin.getClass().getClassLoader();
        Class<?> api=Class.forName("dev.toka.economy.Economy",true,loader);
        Class<?> cause=Class.forName("dev.toka.economy.Cause",true,loader);
        status=Class.forName("dev.toka.economy.Result",true,loader).getMethod("status");
        withdraw=api.getMethod("withdrawOnce",UUID.class,String.class,long.class,cause,String.class);
        deposit=api.getMethod("depositOnce",UUID.class,String.class,long.class,cause,String.class);
        refundWithdrawal=api.getMethod("refundWithdrawalOnce",UUID.class,cause,String.class,cause);
        Method transferOnce=null,holds=null,exists=null;
        try{
            transferOnce=api.getMethod("transferOnce",UUID.class,UUID.class,String.class,long.class,cause,String.class);
            holds=api.getMethod("has",UUID.class,String.class,long.class);
            exists=api.getMethod("hasAccount",UUID.class);
        }catch(NoSuchMethodException older){transferOnce=null;holds=null;exists=null;}
        transfer=transferOnce;has=holds;hasAccount=exists;
        Method of=cause.getMethod("of",String.class,String.class);
        purchaseCause=of.invoke(null,"land-purchase","tokaLand");compensateCause=of.invoke(null,"land-compensate","tokaLand");
        saleCause=of.invoke(null,"land-sale","tokaLand");
    }
    public void disconnect(){available=false;}
    /** A treasury is used only when tokaEconomy can move money between accounts and the account exists; otherwise there is none. */
    private boolean usable(UUID account){
        if(account==null||transfer==null||hasAccount==null||!available)return false;
        try{return (Boolean)hasAccount.invoke(null,account);}
        catch(ReflectiveOperationException|LinkageError failure){return false;}
    }
    @Override public CompletableFuture<String> debit(UUID player,UUID beneficiary,String currency,long amount,String operation){
        return !usable(beneficiary)?invoke(withdraw,player,currency,amount,purchaseCause,operation)
            :invoke(transfer,player,beneficiary,currency,amount,purchaseCause,operation);
    }
    @Override public CompletableFuture<String> refund(UUID player,UUID beneficiary,String currency,long amount,String operation){
        return !usable(beneficiary)?invoke(refundWithdrawal,player,purchaseCause,operation,compensateCause)
            :invoke(transfer,beneficiary,player,currency,amount,compensateCause,"refund:"+operation);
    }
    @Override public CompletableFuture<String> credit(UUID player,UUID source,String currency,long amount,String operation){
        return !usable(source)?invoke(deposit,player,currency,amount,saleCause,operation)
            :invoke(transfer,source,player,currency,amount,saleCause,operation);
    }
    @Override public boolean accepts(UUID account){return usable(account);}
    @Override public String covers(UUID account,String currency,long amount){
        if(!available)return "UNAVAILABLE";
        if(!usable(account))return "OK";
        try{return status.invoke(has.invoke(null,account,currency,amount)).toString();}
        catch(ReflectiveOperationException|LinkageError failure){return "UNAVAILABLE";}
    }
    private CompletableFuture<String> invoke(Method method,Object...args){
        if(!available)return CompletableFuture.completedFuture("UNAVAILABLE");
        try{
            Object value=method.invoke(null,args);
            if(!(value instanceof CompletableFuture<?> result))throw new IllegalStateException("Unexpected economy API");
            return result.thenApply(reply->{try{return status.invoke(reply).toString();}catch(ReflectiveOperationException failure){throw new java.util.concurrent.CompletionException(failure);}});
        }catch(ReflectiveOperationException|LinkageError failure){return CompletableFuture.failedFuture(failure);}
    }
}
