package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.toka.land.command.LandCommand;
import dev.toka.land.form.LandWindow;
import dev.toka.land.model.LandClaim;
import dev.toka.land.model.LandIndex;
import dev.toka.land.protection.*;
import dev.toka.land.storage.DatasetRepository;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * Switchboard: builds the services, registers listeners and the command, and exposes the entry points other
 * plugins call by reflection. The work itself lives in the service classes of this package.
 */
public final class Main extends PluginBase {
    private static volatile Main instance;

    private DocumentStore store;
    private LandService lands;
    private LegacyLink legacyLink;
    public LandService lands() { return lands; }
    private Providers providers;
    private ProtectionQueries queries;
    private Trading trading;
    private dev.toka.land.lease.Leases leases;
    private dev.toka.land.lease.Auctions auctions;
    private Management management;
    private Facilities facilities;
    private DatasetAPI dataset;
    private Texts texts;
    private TopbarService topbar;
    private LandWindow window;
    private PlayerNames playerNames;
    private ExplosionRules explosionRules;
    private SelectionToolListener selectionTool;
    private LandCommand command;
    private volatile boolean ready;
    private volatile boolean ownership;
    private volatile boolean maintenanceLocked;

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
        playerNames = new PlayerNames(getServer());
        texts = new Texts(this, playerNames);
        try {
            var config = getConfig();
            explosionRules = ExplosionRules.parse(config.getString("protection.explosions.mode", "cancel-whole"),
                    config.getBoolean("protection.explosions.environment-protection", true),
                    config.getInt("protection.explosions.attribution-max-depth", 32));
            Backend backend = Backend.valueOf(config.getString("storage.backend", "SQLITE").strip().toUpperCase(Locale.ROOT));
            Path pluginDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path directory = pluginDirectory.resolve(config.getString("storage.directory", "storage")).normalize();
            if (!directory.startsWith(pluginDirectory)) throw new IllegalArgumentException("storage.directory must remain inside the plugin data folder");
            store = DocumentStore.open(new StoreConfig(backend, directory, config.getInt("storage.schema-version", 1)));
            lands = new LandService(new DatasetRepository(store));
            // ownership.active is switched on by hand only after the previous manager is disabled; Land never probes other plugins.
            ownership = config.getBoolean("ownership.active", false);

            providers = new Providers(this);
            queries = new ProtectionQueries(this);
            trading = new Trading(this, lands, store);
            management = new Management(this, lands, trading, playerNames);
            leases = new dev.toka.land.lease.Leases(this, new dev.toka.land.lease.LeaseBook(store));
            auctions = new dev.toka.land.lease.Auctions(this, leases, new dev.toka.land.lease.AuctionBook(store));
            facilities = new Facilities(this, lands, config);
            dataset = new DatasetAPI(lands, trading);
            topbar = new TopbarService(this);
            window = new LandWindow(this);
            registerListeners();
            ready = true;
            trading.connectEconomy();
            legacyLink.connect();
            registerCommand();
            getServer().getScheduler().scheduleRepeatingTask(this, () -> {
                if (!lands.healthy()) lands.recover().thenAccept(ok -> { if (ok) texts.info("log.recovered"); });
                trading.maintain();
                if (leases != null && ownership) leases.maintain();
            }, 200);
            getServer().getScheduler().scheduleRepeatingTask(this, () -> { if (auctions != null && ownership) auctions.tick(); }, 20);
            texts.info("log.loaded", lands.snapshot().index().all().size());
        } catch (Exception error) {
            ready = false;
            texts.error("log.loadFailed", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerListeners() {
        var plugins = getServer().getPluginManager();
        legacyLink = new LegacyLink(this);
        plugins.registerEvents(legacyLink, this);
        var tracker = new ClaimTracker(this);
        plugins.registerEvents(tracker, this);
        getServer().getScheduler().scheduleRepeatingTask(this, tracker::reconcile, 20);
        getServer().getScheduler().scheduleRepeatingTask(this, () -> topbar.refresh(), 20);
        plugins.registerEvents(topbar, this);
        plugins.registerEvents(window, this);
        plugins.registerEvents(trading, this);
        plugins.registerEvents(management, this);
        plugins.registerEvents(new PlayerProtectionListener(this), this);
        plugins.registerEvents(new PvpListener(this), this);
        plugins.registerEvents(new NaturalChangeListener(this), this);
        var explosions = new ExplosionProtectionListener(Main::ownsLand, queries::cancelUnknownBlastRange,
                queries::cancelEntityExplosion, () -> getServer().getTick(), key -> texts.warn(key));
        plugins.registerEvents(explosions, this);
        getServer().getScheduler().scheduleRepeatingTask(this, explosions::prune, 20);
        if (!getConfig().getBoolean("selection.enabled", true)) return;
        var item = cn.nukkit.item.Item.fromString(getConfig().getString("selection.tool", "290:0"));
        if (item.getId() == 0) { texts.warn("log.selectionToolInvalid"); return; }
        selectionTool = new SelectionToolListener(this, item);
        plugins.registerEvents(selectionTool, this);
    }

    /** /land is only registered when no other plugin owns it, so two managers never answer the same command. */
    private void registerCommand() {
        command = new LandCommand(this);
        var commands = getServer().getCommandMap();
        var existing = commands.getCommand("land");
        if (existing != null && existing != command) {
            texts.warn("log.commandTaken");
            command = null;
        } else if (!(commands.register("tokaLand", command) && commands.getCommand("land") == command)) {
            texts.warn("log.commandFailed");
            command = null;
        }
    }

    @Override public void onDisable() {
        ready = false;
        try {
            closeAll();
        } finally {
            if (instance == this) instance = null;
        }
    }

    private void closeAll() {
        if (command != null) {
            var commands = getServer().getCommandMap();
            commands.getCommands().entrySet().removeIf(entry -> entry.getValue() == command);
            command.unregister(commands);
        }
        if (topbar != null) topbar.removeAll();
        // Leases first: a rent still in flight must not write into a store that is closing.
        if (auctions != null) auctions.close();
        if (leases != null) leases.close();
        try {
            if (trading != null) trading.close();
            if (lands != null) lands.close();
        } catch (IOException error) {
            texts.error("log.writePending", error);
            return;
        }
        if (store != null) try { store.close(); } catch (IOException error) { texts.error("log.storeCloseFailed", error); }
    }

    /** /land reload: reads config and language files again and rebuilds what was built from them. */
    public void reloadSettings() {
        reloadConfig();
        var config = getConfig();
        explosionRules = ExplosionRules.parse(config.getString("protection.explosions.mode", "cancel-whole"),
                config.getBoolean("protection.explosions.environment-protection", true),
                config.getInt("protection.explosions.attribution-max-depth", 32));
        ownership = config.getBoolean("ownership.active", false);
        trading.reloadPricing();
        facilities.reload(config);
        texts.clear();
    }

    // ---- State and internal accessors -------------------------------------------------------------------------

    public static Main instance() { return instance; }
    public boolean isReady() { return ready && lands != null && lands.healthy(); }
    /** True only when Land is healthy and has taken over protection. */
    public static boolean ownsLand() { return instance != null && instance.isReady() && instance.ownership; }
    public boolean maintenanceLocked() { return maintenanceLocked; }
    public Providers providers() { return providers; }
    public LandIndex index() { return lands.snapshot().index(); }
    public ExplosionRules explosionRules() { return explosionRules; }
    void requirePrimaryThread() { if (!getServer().isPrimaryThread()) throw new IllegalStateException("Land protection API requires server thread"); }
    private static Main available() {
        Main main = instance;
        if (main == null || !main.isReady()) throw new IllegalStateException("Land unavailable");
        main.requirePrimaryThread();
        return main;
    }

    // ---- Entry points for commands and listeners --------------------------------------------------------------

    public List<LandClaim> findAllAt(String world, int x, int y, int z) { return index().findAllAt(world, x, y, z); }
    public Optional<LandClaim> getClaim(UUID id) { return index().get(id); }
    public Collection<LandClaim> allClaims() { return index().all(); }
    public SelectionToolListener selectionTool() { return selectionTool; }
    public Management management() { return management; }
    public Trading trading() { return trading; }
    public dev.toka.land.lease.Leases leases() { return leases; }
    public dev.toka.land.lease.Auctions auctions() { return auctions; }
    public Facilities facilities() { return facilities; }
    public Texts texts() { return texts; }
    public ProtectionQueries queries() { return queries; }
    public LandWindow window() { return window; }

    // ---- Entry points other plugins call by reflection; names and signatures are a contract --------------------

    /** Main-thread API. UNAVAILABLE must never be interpreted as permission to edit. */
    public static String queryProtectionJson(Player player, String world, int x, int y, int z, String action) {
        Main main = instance;
        if (main == null || !ownsLand()) return ProtectionQueries.notActive();
        main.requirePrimaryThread();
        return main.queries.query(player, world, x, y, z, action);
    }

    /** Main-thread, owner-only list for the player's /tpl menu. */
    public static String queryTeleportTargetsJson(Player player) {
        Main main = instance;
        if (main == null || !main.isReady()) return TeleportQueries.reply("UNAVAILABLE", new JsonArray());
        main.requirePrimaryThread();
        return TeleportQueries.targets(main.index(), player);
    }

    /** Stable UUID lookup; names are accepted only when unique among targets the player may use. */
    public static String queryTeleportTargetJson(Player player, String idOrName) {
        Main main = instance;
        if (main == null || !main.isReady()) return TeleportQueries.reply("UNAVAILABLE", new JsonArray());
        main.requirePrimaryThread();
        return TeleportQueries.target(main.index(), player, idOrName);
    }

    /** Public, minimal label lookup for a configured sign; exposes no coordinates or permission data. */
    public static String queryTeleportDisplayJson(String idOrName) {
        Main main = instance;
        if (main == null || !main.isReady()) return TeleportQueries.reply("UNAVAILABLE", new JsonArray());
        main.requirePrimaryThread();
        return TeleportQueries.display(main.index(), idOrName);
    }

    // API: other plugins register their callbacks here
    /** See {@link Providers}: callbacks and JSON keep Land model classes out of other plugins' loaders. */
    public static void registerProtectedAuthority(String providerName, BiPredicate<Player, String> callback) { available().providers.registerAuthority(providerName, callback); }
    public static void unregisterProtectedAuthority(String providerName) { Main main = instance; if (main != null) { main.requirePrimaryThread(); main.providers.unregisterAuthority(providerName); } }
    public static void registerAccessSubjectProvider(String providerId, String pluginName, BiPredicate<Player, String> resolver) { available().providers.registerGroups(providerId, pluginName, resolver); }
    public static void unregisterAccessSubjectProvider(String providerId) { Main main = instance; if (main != null) { main.requirePrimaryThread(); main.providers.unregisterGroups(providerId); } }
    /** Region rules and price adjustments (SELECT, ADJUST). */
    public static void registerPurchasePolicy(String pluginName, BiFunction<Player, String, String> callback) { available().providers.registerPricing(pluginName, callback); }
    public static void unregisterPurchasePolicy(String pluginName) { if (instance != null && instance.providers != null) instance.providers.unregisterPricing(pluginName); }

    // API: trusted plugins (administration)
    /** Trusted plugin administration API; a maintenance lock always defeats player privileges. */
    public static void setMaintenanceLocked(boolean locked) {
        Main main = instance;
        if (main == null) throw new IllegalStateException("Land unavailable");
        main.requirePrimaryThread();
        main.maintenanceLocked = locked;
    }

    /** Called by tokaWorlds after renaming a world; claims and teleport points follow. Completes with the number of claims changed. */
    public static CompletableFuture<Integer> renameWorld(String from, String to) {
        Main main = instance;
        if (main == null || !main.isReady()) throw new IllegalStateException("Land unavailable");
        main.requirePrimaryThread();
        return main.dataset.renameWorld(from, to);
    }

    /** Number of claims in a world, so tokaWorlds can report what a rename will change. */
    public static int countClaimsInWorld(String world) {
        Main main = instance;
        if (main == null || !main.isReady()) throw new IllegalStateException("Land unavailable");
        main.requirePrimaryThread();
        return (int) main.index().all().stream().filter(claim -> claim.world().equals(world)).count();
    }

    /**
     * The claims at a point as a JSON array of {id, kind, type}, "[]" when there are none, or null when Land is not ready
     * (so a caller never mistakes "unknown" for "unclaimed"). Safe from any thread: it reads an immutable snapshot.
     */
    public static String claimsAtJson(String world, int x, int y, int z) {
        Main main = instance;
        if (main == null || !main.isReady()) return null;
        JsonArray rows = new JsonArray();
        for (var claim : main.index().findAllAt(world, x, y, z)) {
            JsonObject row = new JsonObject();
            row.addProperty("id", claim.id().toString());
            row.addProperty("kind", claim.kind().name());
            row.addProperty("type", claim.type());
            rows.add(row);
        }
        return rows.toString();
    }

    /** Whether the player owns at least one claim; false while Land is not ready. Thread-safe. */
    public static boolean ownsAnyClaim(UUID player) {
        Main main = instance;
        return main != null && main.isReady() && !main.index().ownedBy(player).isEmpty();
    }
}
