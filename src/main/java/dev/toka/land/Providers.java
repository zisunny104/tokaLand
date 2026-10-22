package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.toka.land.model.AccessSubject;
import dev.toka.land.model.ClaimPermission;
import dev.toka.land.model.LandClaim;
import dev.toka.land.storage.DatasetRepository;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * Services other plugins register with Land: group membership, external authority for protected claims, and
 * pricing. Callbacks exchange strings so no Land class crosses a plugin loader. A provider that is missing,
 * replaced, disabled or throwing is dropped and counts as a denial, never as permission.
 */
public final class Providers {
    private record Registration<T>(String pluginName, Plugin identity, T callback) {}

    private final Main plugin;
    private final Map<String, Registration<BiPredicate<Player, String>>> groups = new HashMap<>();
    private volatile Registration<BiPredicate<Player, String>> authority;
    private Registration<BiFunction<Player, String, String>> pricing;
    private boolean pricingLost;

    Providers(Main plugin) { this.plugin = plugin; }

    private Plugin enabled(String name, String what) {
        Plugin found = plugin.getServer().getPluginManager().getPlugin(Objects.requireNonNull(name));
        if (found == null || !found.isEnabled()) throw new IllegalArgumentException(what + " must be enabled");
        return found;
    }

    private boolean alive(Registration<?> registration) {
        Plugin current = plugin.getServer().getPluginManager().getPlugin(registration.pluginName());
        return current == registration.identity() && current.isEnabled();
    }

    /**
     * The provider keeps its own legacy rules; ordinary claim membership is no replacement. One provider at a time.
     * TODO(migration): the legacy qx permission rules for PROTECT claims still need an owner once zero is gone.
     */
    public void registerAuthority(String name, BiPredicate<Player, String> callback) {
        Plugin provider = enabled(name, "Authority provider");
        Registration<?> existing = authority;
        if (existing != null && !existing.pluginName().equals(name)) throw new IllegalStateException("A different authority provider is already registered");
        authority = new Registration<>(name, provider, Objects.requireNonNull(callback));
    }

    public void unregisterAuthority(String name) {
        Registration<?> current = authority;
        if (current != null && current.pluginName().equals(name)) authority = null;
    }

    public void registerGroups(String providerId, String name, BiPredicate<Player, String> resolver) {
        String key = Objects.requireNonNull(providerId).strip().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) throw new IllegalArgumentException("providerId is required");
        groups.put(key, new Registration<>(name, enabled(name, "Access provider plugin"), Objects.requireNonNull(resolver)));
    }

    public void unregisterGroups(String providerId) {
        if (providerId != null) groups.remove(providerId.strip().toLowerCase(Locale.ROOT));
    }

    /** Whether the player is the subject: by UUID directly, or through a registered group resolver. */
    public boolean subjectMatches(Player player, AccessSubject subject) {
        if (subject.type() == AccessSubject.Type.PLAYER_UUID) return subject.id().equals(player.getUniqueId().toString());
        if (subject.type() != AccessSubject.Type.GROUP) return false;
        var provider = groups.get(subject.provider());
        if (provider == null) return false;
        if (!alive(provider)) { groups.remove(subject.provider()); return false; }
        try { return provider.callback().test(player, subject.id()); }
        catch (RuntimeException | LinkageError failure) {
            groups.remove(subject.provider());
            plugin.getLogger().warning("Land 群組授權服務失效，維持拒絕：" + failure.getClass().getSimpleName());
            return false;
        }
    }

    public boolean externalAuthority(Player player, LandClaim claim, ClaimPermission action) {
        var provider = authority;
        if (provider == null) return false;
        if (!alive(provider)) { authority = null; return false; }
        JsonObject request = new JsonObject();
        request.add("claim", DatasetRepository.encode(claim));
        request.addProperty("action", action.name());
        try { return provider.callback().test(player, request.toString()); }
        catch (RuntimeException | LinkageError failure) {
            authority = null;
            plugin.getLogger().warning("Land 外部保護授權失效，維持拒絕：" + failure.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Region rules and price adjustments are optional: without a provider Land uses its own defaults.
     * TODO(migration): zero is the only provider today; replace it when Region and the tax/discount rules have an owner.
     */
    public void registerPricing(String name, BiFunction<Player, String, String> callback) {
        Plugin provider = enabled(name, "Purchase policy provider");
        if (pricing != null && pricing.identity() != provider && pricing.identity().isEnabled())
            throw new IllegalStateException("Purchase policy already registered");
        pricing = new Registration<>(name, provider, Objects.requireNonNull(callback));
        pricingLost = false;
    }

    /**
     * Asks the region/pricing provider (SELECT or ADJUST). Empty when none was ever registered, so the caller applies
     * its defaults; a provider that disappeared after registering fails closed instead of silently changing prices.
     */
    public Optional<JsonObject> ask(Player player, JsonObject request) {
        var provider = pricing;
        if (provider == null) {
            if (pricingLost) throw new IllegalStateException("PURCHASE_POLICY_UNAVAILABLE");
            return Optional.empty();
        }
        if (!alive(provider)) { pricing = null; pricingLost = true; throw new IllegalStateException("PURCHASE_POLICY_UNAVAILABLE"); }
        var reply = JsonParser.parseString(provider.callback().apply(player, request.toString())).getAsJsonObject();
        if (!"OK".equals(reply.get("status").getAsString())) throw new IllegalStateException("PURCHASE_POLICY_DENIED");
        return Optional.of(reply);
    }
}
