package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.JsonArray;
import dev.toka.land.command.LandCommand;
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
    /** Stays false until the cutover is verified; with it false Land never takes over protection. */
    private static final boolean PROTECTION_READY = false;
    private static volatile Main instance;

    private DocumentStore store;
    private LandService lands;
    private Providers providers;
    private ProtectionQueries queries;
    private Trading trading;
    private Management management;
    private Facilities facilities;
    private DatasetAPI dataset;
    private Texts texts;
    private TopbarService topbar;
    private PlayerNames playerNames;
    private ExplosionRules explosionRules;
    private SelectionToolListener selectionTool;
    private LandCommand command;
    private volatile boolean ready;
    private boolean ownership;
    private volatile boolean maintenanceLocked;

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
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
            // The cutover tool flips ownership.active only after the previous manager is disabled; Land never probes other plugins.
            ownership = PROTECTION_READY && config.getBoolean("ownership.active", false);
            if (config.getBoolean("ownership.active", false) && !ownership) getLogger().error("Land 保護尚未完成驗證；保持待命。");

            playerNames = new PlayerNames(getServer());
            providers = new Providers(this);
            queries = new ProtectionQueries(this);
            texts = new Texts(this, playerNames);
            trading = new Trading(this, lands, store);
            management = new Management(this, lands, trading, playerNames);
            facilities = new Facilities(this, lands, config);
            dataset = new DatasetAPI(lands, trading);
            topbar = new TopbarService(this);
            registerListeners();
            ready = true;
            trading.connectEconomy();
            registerCommand();
            getServer().getScheduler().scheduleRepeatingTask(this, trading::maintain, 200);
            getLogger().info("Land 資料載入完成，共 " + lands.snapshot().index().all().size() + " 筆；儲存格式：" + backend);
        } catch (Exception error) {
            ready = false;
            getLogger().error("Land 未能載入；為避免以空資料覆蓋原資料，插件將停用。", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerListeners() {
        var plugins = getServer().getPluginManager();
        plugins.registerEvents(topbar, this);
        plugins.registerEvents(trading, this);
        plugins.registerEvents(new PlayerProtectionListener(this), this);
        plugins.registerEvents(new NaturalChangeListener(this), this);
        plugins.registerEvents(new PvpListener(this), this);
        var explosions = new ExplosionProtectionListener(Main::ownsLand, queries::cancelUnknownBlastRange,
                queries::cancelEntityExplosion, () -> getServer().getTick(), line -> getLogger().warning(line));
        plugins.registerEvents(explosions, this);
        getServer().getScheduler().scheduleRepeatingTask(this, explosions::prune, 20);
        if (!getConfig().getBoolean("selection.enabled", true)) return;
        var item = cn.nukkit.item.Item.fromString(getConfig().getString("selection.tool", "290:0"));
        if (item.getId() == 0) { getLogger().warning("選地工具設定無效，已停用選區點擊；請檢查 selection.tool。"); return; }
        selectionTool = new SelectionToolListener(this, item);
        plugins.registerEvents(selectionTool, this);
    }

    /** /land is only registered when no other plugin owns it, so two managers never answer the same command. */
    private void registerCommand() {
        command = new LandCommand(this);
        var commands = getServer().getCommandMap();
        var existing = commands.getCommand("land");
        if (existing != null && existing != command) {
            getLogger().warning("/land 已由其他插件註冊；請先停用該入口再重啟。此次不註冊替代指令。");
            command = null;
        } else if (commands.register("tokaLand", command) && commands.getCommand("land") == command) {
            getLogger().info("指令 /land 已註冊，Tab 候選已啟用。");
        } else {
            getLogger().error("無法註冊根指令 /land；請檢查指令衝突。未啟用其他替代入口。");
            command = null;
        }
    }

    @Override public void onDisable() {
        ready = false;
        if (command != null) {
            var commands = getServer().getCommandMap();
            commands.getCommands().entrySet().removeIf(entry -> entry.getValue() == command);
            command.unregister(commands);
        }
        if (topbar != null) topbar.removeAll();
        try {
            if (trading != null) trading.close();
            if (lands != null) lands.close();
        } catch (IOException error) {
            getLogger().error("Land 寫入工作尚未完成，保留儲存鎖直到程序結束。", error);
            return;
        }
        if (store != null) try { store.close(); } catch (IOException error) { getLogger().error("關閉 Land 儲存庫失敗。", error); }
        if (instance == this) instance = null;
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
    public Facilities facilities() { return facilities; }
    public Texts texts() { return texts; }
    public ProtectionQueries queries() { return queries; }

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

    /** See {@link Providers}: callbacks and JSON keep Land model classes out of other plugins' loaders. */
    public static void registerProtectedAuthority(String providerName, BiPredicate<Player, String> callback) { available().providers.registerAuthority(providerName, callback); }
    public static void unregisterProtectedAuthority(String providerName) { Main main = instance; if (main != null) { main.requirePrimaryThread(); main.providers.unregisterAuthority(providerName); } }
    public static void registerAccessSubjectProvider(String providerId, String pluginName, BiPredicate<Player, String> resolver) { available().providers.registerGroups(providerId, pluginName, resolver); }
    public static void unregisterAccessSubjectProvider(String providerId) { Main main = instance; if (main != null) { main.requirePrimaryThread(); main.providers.unregisterGroups(providerId); } }
    /** Region rules and price adjustments (SELECT, ADJUST). */
    public static void registerPurchasePolicy(String pluginName, BiFunction<Player, String, String> callback) { available().providers.registerPricing(pluginName, callback); }

    /** Trusted plugin administration API; a maintenance lock always defeats player privileges. */
    public static void setMaintenanceLocked(boolean locked) {
        Main main = instance;
        if (main == null) throw new IllegalStateException("Land unavailable");
        main.requirePrimaryThread();
        main.maintenanceLocked = locked;
        main.getLogger().info("Land maintenance lock=" + locked);
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

    public static String exportDatasetJson() {
        Main main = instance;
        if (main == null || !main.isReady()) throw new IllegalStateException("Land unavailable");
        return main.dataset.export();
    }

    public static CompletableFuture<String> replaceDatasetJson(String expectedRevision, String json) {
        Main main = instance;
        if (main == null || !main.isReady()) throw new IllegalStateException("Land unavailable");
        return main.dataset.replace(expectedRevision, json);
    }
}
