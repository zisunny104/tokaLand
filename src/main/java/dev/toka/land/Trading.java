package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginDisableEvent;
import cn.nukkit.plugin.Plugin;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.toka.land.model.*;
import dev.toka.land.protection.SelectionToolListener;
import dev.toka.land.purchase.EconomyLedger;
import dev.toka.land.purchase.LandPricing;
import dev.toka.land.purchase.PurchaseCoordinator;
import dev.toka.land.purchase.SaleCoordinator;
import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Buying and selling land: price from {@link LandPricing}, discounts and region rules from the registered
 * provider, payment through tokaEconomy, and recovery of unfinished transactions.
 */
public final class Trading implements Listener, AutoCloseable {
    private static final Gson GSON = new Gson();
    private final Main plugin;
    private final PurchaseCoordinator purchases;
    private final SaleCoordinator sales;
    private volatile LandPricing pricing;
    private volatile EconomyLedger ledger;
    private Plugin economy;
    private final AtomicBoolean recovering = new AtomicBoolean();

    Trading(Main plugin, LandService lands, DocumentStore store) throws IOException {
        this.plugin = plugin;
        reloadPricing();
        this.purchases = new PurchaseCoordinator(lands, store, () -> ledger);
        this.sales = new SaleCoordinator(lands, store, () -> ledger);
    }

    /** Reads the price settings again; a quote in progress keeps the old object. */
    void reloadPricing() {
        var config = plugin.getConfig();
        this.pricing = new LandPricing(config.getString("pricing.currency", "money"), config.getDouble("pricing.per-block", 6.3),
                config.getDouble("pricing.per-block-flat", 73.1), config.getDouble("pricing.refund-rate", 0.7));
    }

    public boolean hasPending() { return purchases.hasPending() || sales.hasPending(); }

    public boolean hasPending(UUID claimId) { return purchases.hasPending(claimId) || sales.hasPending(claimId); }

    /** Asks the region provider whether the point may be selected, and whether the area is priced flat. */
    public boolean ignoreHeight(Player player, SelectionToolListener.Point point) {
        if (!Main.ownsLand() || plugin.maintenanceLocked()) throw new IllegalStateException("LAND_NOT_ACTIVE");
        if (!player.hasPermission("tokaland.admin") && plugin.findAllAt(player.getLevel().getFolderName(), player.getFloorX(),
                player.getFloorY(), player.getFloorZ()).stream().anyMatch(c -> c.kind() == ClaimKind.PLAYER))
            throw new IllegalStateException("SELECTION_INSIDE_LAND");
        JsonObject request = new JsonObject();
        request.addProperty("action", "SELECT");
        request.add("point", GSON.toJsonTree(point));
        return plugin.providers().ask(player, request).map(reply -> reply.get("ignoreHeight").getAsBoolean())
                .orElse(plugin.getConfig().getBoolean("selection.default-ignore-height", false));
    }

    /** What a selection holds and what it would cost, both as footprint and as volume; the area decides which of the two is charged. */
    public record Preview(long area, long volume, long flatPrice, long volumePrice, boolean flat) { }

    public Preview preview(Player player, SelectionToolListener.Selection draft) {
        var part = draft.geometry();
        JsonObject request = new JsonObject();
        request.addProperty("action", "ADJUST");
        request.add("first", GSON.toJsonTree(draft.first()));
        request.add("second", GSON.toJsonTree(draft.second()));
        request.addProperty("ignoreHeight", draft.ignoreHeight());
        request.addProperty("ownedCount", plugin.index().ownedBy(player.getUniqueId()).size());
        JsonObject none = new JsonObject();
        none.addProperty("offer", 1.0);
        none.addProperty("tax", 1.0);
        JsonObject adjustment = plugin.providers().ask(player, request).orElse(none);
        double offer = adjustment.get("offer").getAsDouble(), tax = adjustment.get("tax").getAsDouble();
        return new Preview(LandPricing.area(part), LandPricing.volume(part), pricing.quoteFlat(part, offer, tax), pricing.quoteVolume(part, offer, tax), draft.ignoreHeight());
    }

    public CompletableFuture<PurchaseCoordinator.Outcome> purchase(Player player, String name) {
        plugin.requirePrimaryThread();
        if (!Main.ownsLand() || plugin.maintenanceLocked()) throw new IllegalStateException("LAND_NOT_ACTIVE");
        if (ReservedNames.is(name)) throw new IllegalArgumentException("RESERVED_NAME");
        var tool = plugin.selectionTool();
        var draft = tool == null ? null : tool.get(player.getUniqueId());
        if (draft == null) throw new IllegalStateException("SELECTION_REQUIRED");
        var part = draft.geometry();
        if (draft.teleport() == null) throw new IllegalStateException("TELEPORT_REQUIRED");
        UUID owner = player.getUniqueId();
        JsonObject request = new JsonObject();
        request.addProperty("action", "ADJUST");
        request.add("first", GSON.toJsonTree(draft.first()));
        request.add("second", GSON.toJsonTree(draft.second()));
        request.addProperty("ignoreHeight", draft.ignoreHeight());
        request.addProperty("ownedCount", plugin.index().ownedBy(owner).size());
        JsonObject none = new JsonObject();
        none.addProperty("offer", 1.0);
        none.addProperty("tax", 1.0);
        JsonObject adjustment = plugin.providers().ask(player, request).orElse(none);
        long amount = pricing.quote(part, adjustment.get("offer").getAsDouble(), adjustment.get("tax").getAsDouble());
        // The treasury (if the provider names one) receives the whole price and later pays the refund.
        UUID treasury = adjustment.has("beneficiary") ? UUID.fromString(adjustment.get("beneficiary").getAsString()) : null;
        // An account the economy cannot pay into is treated as no treasury, so the refund is never drawn from money it never got.
        var money = ledger;
        if (treasury != null && money != null && !money.accepts(treasury)) treasury = null;
        // The refund is fixed now and can never exceed what was paid, so a discount cannot be turned into profit.
        long refundValue = Math.min(pricing.refund(List.of(part)), amount);
        LandClaim claim = new LandClaim(UUID.randomUUID(), name, ClaimKind.PLAYER, "land", owner, null, false, false, List.of(part), List.of(),
                AccessPolicy.privateDefault(), Map.of(), null, player.getName(),
                new ClaimDetails(owner, player.getName(), draft.teleport(), true, refundValue, treasury));
        return purchases.purchase(claim, pricing.currency(), amount, treasury);
    }

    /**
     * Deleting pays the claim's refund value to its current owner, from the account that was paid for it.
     * Claims from before refund values existed keep the old rule: only their creator is refunded, by geometry.
     * If that account cannot cover the refund, deleting needs {@code acceptNoRefund}; nothing is deleted silently.
     */
    public CompletableFuture<PurchaseCoordinator.Outcome> sell(Player player, LandClaim claim, boolean acceptNoRefund) {
        Long value = claim.details().refundValue();
        UUID source = claim.details().refundSource();
        UUID recipient = player.getUniqueId();
        long amount;
        if (value != null) {
            var owner = ClaimAccess.effectiveOwner(plugin.index(), claim.id());
            amount = owner.isPresent() ? value : 0;
            if (owner.isPresent()) recipient = owner.get();
        } else {
            UUID creator = claim.details().creatorId();
            String creatorName = claim.details().creatorName();
            if (creator == null && creatorName != null && !creatorName.isBlank()) throw new IllegalStateException("CREATOR_IDENTITY_UNCONFIRMED");
            amount = player.getUniqueId().equals(creator) ? pricing.refund(claim.selections()) : 0;
        }
        var money = ledger;
        if (amount > 0 && source != null && money != null && !money.covers(source, pricing.currency(), amount).equals("OK")) {
            if (!acceptNoRefund) throw new IllegalStateException("TREASURY_INSUFFICIENT");
            amount = 0;
        }
        return sales.sell(claim, recipient, pricing.currency(), amount, amount > 0 ? source : null);
    }

    /** Called periodically: reconnects tokaEconomy after a reload and finishes interrupted transactions. */
    public void maintain() {
        connectEconomy();
        if (!Main.ownsLand() || !recovering.compareAndSet(false, true)) return;
        var buying = purchases.recover();
        var selling = sales.recover();
        CompletableFuture.allOf(buying, selling).whenComplete((ignored, failure) -> {
            recovering.set(false);
            if (failure != null) { plugin.texts().warn("log.tradeRecoverFailed", failure.getClass().getSimpleName()); return; }
            for (var result : buying.join()) if (!result.status().equals("PENDING")) plugin.texts().info("log.buyRecovered", result.operation(), result.status());
            for (var result : selling.join()) if (!result.status().equals("PENDING")) plugin.texts().info("log.sellRecovered", result.operation(), result.status());
        });
    }

    public void connectEconomy() {
        Plugin current = plugin.getServer().getPluginManager().getPlugin("tokaEconomy");
        if (current == economy && current != null && current.isEnabled()) return;
        disconnect();
        if (current == null || !current.isEnabled()) return;
        try { ledger = new EconomyLedger(current); economy = current; }
        catch (ReflectiveOperationException | LinkageError failure) { plugin.texts().warn("log.tradeApiMissing", failure.getClass().getSimpleName()); }
    }

    private void disconnect() {
        if (ledger != null) ledger.disconnect();
        ledger = null;
        economy = null;
    }

    @EventHandler public void onPluginDisabled(PluginDisableEvent event) {
        if (event.getPlugin() == economy) disconnect();
    }

    /** Closes both ledgers; a transaction still saving keeps the storage lock. */
    @Override public void close() throws IOException {
        disconnect();
        try { sales.close(); } catch (IOException error) { plugin.texts().error("log.salesPending", error); throw error; }
        try { purchases.close(); } catch (IOException error) { plugin.texts().error("log.purchasesPending", error); throw error; }
    }
}
