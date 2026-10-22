package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Position;
import cn.nukkit.utils.DummyBossBar;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.LandClaim;
import dev.toka.land.model.LandIndex;
import dev.toka.land.storage.LandRepository;
import dev.toka.land.storage.DatasetRepository;
import com.google.gson.*;
import dev.toka.i18n.Messages;
import dev.toka.land.model.ClaimAccess;
import dev.toka.land.model.ClaimPermission;
import dev.toka.land.model.ClaimDetails;
import dev.toka.land.model.ProtectionDecision;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class Main extends PluginBase implements Listener {
    // Remove only after the event handlers, consumer adapters and single-writer cutover are verified.
    private static final boolean PROTECTION_READY=false;
    private static volatile Main instance;
    private DocumentStore store;
    private LandService lands;
    private dev.toka.land.protection.ExplosionRules explosionRules;
    private volatile boolean ready;
    private boolean ownership;
    private PlayerNames playerNames;
    private final Map<String,Messages> languages=new HashMap<>();
    private final Map<UUID, Long> fallbackBars = new HashMap<>();
    private final Map<UUID, String> shownClaims = new HashMap<>();
    private java.lang.reflect.Method displayBossBar;
    private java.lang.reflect.Method displayRemove;
    private dev.toka.land.command.LandCommand readCommand;
    private dev.toka.land.protection.SelectionToolListener selectionToolListener;
    private volatile boolean maintenanceLocked;
    private record ProtectedAuthority(String pluginName,Plugin pluginIdentity,java.util.function.BiPredicate<Player,String> callback){}
    private volatile ProtectedAuthority protectedAuthority;

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
        try {
            explosionRules=dev.toka.land.protection.ExplosionRules.parse(
                getConfig().getString("protection.explosions.mode","cancel-whole"),
                getConfig().getBoolean("protection.explosions.environment-protection",true),
                getConfig().getInt("protection.explosions.attribution-max-depth",32));
            Backend backend = Backend.valueOf(getConfig().getString("storage.backend", "SQLITE").strip().toUpperCase(Locale.ROOT));
            int schema = getConfig().getInt("storage.schema-version", 1);
            Path pluginDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path directory = pluginDirectory.resolve(getConfig().getString("storage.directory", "storage")).normalize();
            if (!directory.startsWith(pluginDirectory))
                throw new IllegalArgumentException("storage.directory must remain inside the plugin data folder");
            store = DocumentStore.open(new StoreConfig(backend, directory, schema));
            lands = new LandService(new DatasetRepository(store));
            List<LandClaim> claims = List.copyOf(lands.snapshot().index().all());
            playerNames=new PlayerNames(getServer());
            ownership=PROTECTION_READY&&getConfig().getBoolean("ownership.active",false)&&!legacyOwnership();
            if(getConfig().getBoolean("ownership.active",false)&&!ownership)getLogger().error("Land 保護及交接尚未完成驗證，或 zero 仍在管理領地；保持待命。");
            connectDisplay();
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(new dev.toka.land.protection.PlayerProtectionListener(this), this);
            getServer().getPluginManager().registerEvents(new dev.toka.land.protection.NaturalChangeListener(this),this);
            var explosionListener=new dev.toka.land.protection.ExplosionProtectionListener(
                Main::ownsLand,this::cancelUnknownBlastRange,this::cancelEntityExplosion,()->getServer().getTick(),
                line->getLogger().warning(line));
            getServer().getPluginManager().registerEvents(explosionListener,this);
            getServer().getScheduler().scheduleRepeatingTask(this,explosionListener::prune,20);
            if(getConfig().getBoolean("selection.enabled",true)){
                var configuredTool=cn.nukkit.item.Item.fromString(getConfig().getString("selection.tool","wooden_axe:0"));
                if(configuredTool.getId()==0)getLogger().warning("選地工具設定無效，已停用選區點擊；請檢查 selection.tool。");
                else{
                    selectionToolListener=new dev.toka.land.protection.SelectionToolListener(this,configuredTool);
                    getServer().getPluginManager().registerEvents(selectionToolListener,this);
                }
            }
            ready = true;
            readCommand=new dev.toka.land.command.LandCommand(this);
            var commandMap=getServer().getCommandMap();
            var existingLand=commandMap.getCommand("land");
            if(existingLand!=null&&existingLand!=readCommand){
                getLogger().warning("/land 已由其他插件註冊；依搬遷流程先在 zero 設定 features.land: false 並重啟，再由 tokaLand 註冊 /land。此次不註冊替代指令。");
                readCommand=null;
            }else if(commandMap.register("tokaLand",readCommand)&&commandMap.getCommand("land")==readCommand)
                getLogger().info("指令 /land 已註冊，Tab 候選已啟用。");
            else{
                getLogger().error("無法註冊根指令 /land；請檢查指令衝突。未啟用其他替代入口。");
                readCommand=null;
            }
            getServer().getScheduler().scheduleRepeatingTask(this, this::refreshTopbars, 20);
            getLogger().info("Land 資料載入完成，共 " + claims.size() + " 筆；儲存格式：" + backend);
        } catch (Exception error) {
            ready = false;
            getLogger().error("Land 未能載入；為避免以空資料覆蓋原資料，插件將停用。", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        ready = false;
        if(readCommand!=null){
            var commands=getServer().getCommandMap();
            commands.getCommands().entrySet().removeIf(entry->entry.getValue()==readCommand);
            readCommand.unregister(commands);
        }
        for (Player player : getServer().getOnlinePlayers().values()) removeTopbar(player);
        if(lands!=null)try{lands.close();}catch(IOException error){getLogger().error("Land 寫入工作尚未完成，保留儲存鎖直到程序結束。",error);return;}
        if (store != null) try { store.close(); }
        catch (IOException error) { getLogger().error("關閉 Land 儲存庫失敗。", error); }
        if (instance == this) instance = null;
    }

    public static Main instance() { return instance; }
    public boolean isReady() { return ready&&lands!=null&&lands.healthy(); }
    public static boolean ownsLand(){return instance!=null&&instance.isReady()&&instance.ownership;}
    public boolean protectedEnvironment(Position target){
        if(!ownsLand()||target==null||target.getLevel()==null)return false;
        return maintenanceLocked||!lands.snapshot().index().findAllAt(target.getLevel().getFolderName(),target.getFloorX(),target.getFloorY(),target.getFloorZ()).isEmpty();
    }
    public boolean crossesLandBoundary(cn.nukkit.block.Block from,cn.nukkit.block.Block to,String configKey){
        if(!ownsLand()||!getConfig().getBoolean(configKey,true)||from==null||to==null
            ||from.getLevel()==null||to.getLevel()==null)return false;
        var index=lands.snapshot().index();
        var source=index.findAllAt(from.getLevel().getFolderName(),from.getFloorX(),from.getFloorY(),from.getFloorZ())
            .stream().map(LandClaim::id).collect(java.util.stream.Collectors.toSet());
        var destination=index.findAllAt(to.getLevel().getFolderName(),to.getFloorX(),to.getFloorY(),to.getFloorZ())
            .stream().map(LandClaim::id).collect(java.util.stream.Collectors.toSet());
        return (!source.isEmpty()||!destination.isEmpty())&&!source.equals(destination);
    }
    private boolean cancelUnknownBlastRange(){return explosionRules!=null&&explosionRules.mode()==dev.toka.land.protection.ExplosionRules.Mode.CANCEL_WHOLE;}
    private boolean cancelEntityExplosion(cn.nukkit.entity.Entity source,Position center,double force){
        if(explosionRules==null||center==null||center.getLevel()==null||!Double.isFinite(force)||force<0)return true;
        double radius=force*2d;
        var bounds=new dev.toka.land.protection.ExplosionRules.Bounds(center.getLevel().getFolderName(),
            Math.floor(center.x-radius-1d),Math.floor(center.y-radius-1d),Math.floor(center.z-radius-1d),
            Math.ceil(center.x+radius+1d),Math.ceil(center.y+radius+1d),Math.ceil(center.z+radius+1d));
        var index=lands.snapshot().index();
        var attribution=dev.toka.land.protection.ExplosionAttribution.resolve(source,explosionRules.attributionMaxDepth());
        java.util.function.Predicate<LandClaim> permitted=claim->{
            if(maintenanceLocked)return false;
            var player=attribution.playerId()==null?null:getServer().getOnlinePlayers().values().stream()
                .filter(candidate->candidate.getUniqueId().equals(attribution.playerId())).findFirst().orElse(null);
            boolean administrator=player!=null&&player.hasPermission("tokaland.admin");
            return switch(claim.details().protection()){
                case OPEN -> true;
                case PRIVATE -> administrator||dev.toka.land.model.ClaimAccess.allows(index,claim.id(),attribution.playerId(),ClaimPermission.BUILD,false);
                case PROTECT -> administrator||player!=null&&externalAuthority(player,claim,ClaimPermission.BUILD);
                // LOCK remains closed until its durable enable and audit path exists.
                case LOCK -> false;
            };
        };
        var decision=explosionRules.evaluate(index,bounds,attribution,permitted);
        boolean cancel=maintenanceLocked&&!decision.affectedClaims().isEmpty()||decision.cancel();
        if(cancel)getLogger().info("已攔截領地範圍內爆炸："+decision.reason()+"，命中領地="+decision.affectedClaims().size());
        return cancel;
    }
    /** Main-thread API. UNAVAILABLE must never be interpreted as permission to edit. */
    public static String queryProtectionJson(Player player,String world,int x,int y,int z,String action){
        Main main=instance;
        if(main==null||!ownsLand())return protectionReply("UNAVAILABLE",false,"LAND_NOT_ACTIVE",List.of(),List.of());
        main.requirePrimaryThread();
        if(main.maintenanceLocked)return protectionReply("DENY",false,"MAINTENANCE_LOCK",List.of(),List.of());
        if(player==null||!player.isOnline()||world==null||world.isBlank()||action==null)
            return protectionReply("INVALID_REQUEST",false,"INVALID_ACTOR_OR_TARGET",List.of(),List.of());
        final ClaimPermission permission;
        try{permission=ClaimPermission.valueOf(action.strip().toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException invalid){return protectionReply("INVALID_REQUEST",false,"UNKNOWN_ACTION",List.of(),List.of());}
        var index=main.lands.snapshot().index();var hits=index.findAllAt(world,x,y,z);
        boolean administrator=player.hasPermission("tokaland.admin");
        // Durable audit and actual-use auditing are a separate prerequisite. Never enable LOCK bypass prematurely.
        var actor=new ProtectionDecision.Actor(player.getUniqueId(),administrator,player.hasPermission("tokaland.lock-bypass"),false);
        var result=ProtectionDecision.evaluate(index,hits,actor,permission,false,(claim,requested)->main.externalAuthority(player,claim,requested));
        String reason=result.reason();
        if(!result.allowed()&&hits.stream().anyMatch(c->result.blockingClaims().contains(c.id())&&c.details().protection()==ClaimDetails.ProtectionMode.LOCK))
            reason="LOCK_BYPASS_AUDIT_UNAVAILABLE";
        return protectionReply(result.allowed()?"ALLOW":"DENY",result.allowed(),reason,hits.stream().map(LandClaim::id).toList(),result.blockingClaims());
    }
    /** Platform Java callback and JSON avoid exposing Land model classes to another plugin loader.
     * The provider must preserve its own legacy qx rules; ordinary claim membership is not a replacement.
     */
    public static void registerProtectedAuthority(String providerName,java.util.function.BiPredicate<Player,String> callback){
        Main main=instance;if(main==null||!main.isReady())throw new IllegalStateException("Land unavailable");
        main.requirePrimaryThread();
        var plugin=main.getServer().getPluginManager().getPlugin(Objects.requireNonNull(providerName));
        if(plugin==null||!plugin.isEnabled())throw new IllegalArgumentException("Authority provider must be enabled");
        ProtectedAuthority existing=main.protectedAuthority;
        if(existing!=null&&!existing.pluginName().equals(providerName))throw new IllegalStateException("A different authority provider is already registered");
        main.protectedAuthority=new ProtectedAuthority(providerName,plugin,Objects.requireNonNull(callback));
    }
    public static void unregisterProtectedAuthority(String providerName){
        Main main=instance;if(main==null)return;main.requirePrimaryThread();
        if(main.protectedAuthority!=null&&main.protectedAuthority.pluginName().equals(providerName))main.protectedAuthority=null;
    }
    /** Trusted plugin administration API; a maintenance lock always defeats player privileges. */
    public static void setMaintenanceLocked(boolean locked){
        Main main=instance;if(main==null)throw new IllegalStateException("Land unavailable");main.requirePrimaryThread();
        main.maintenanceLocked=locked;
        main.getLogger().info("Land maintenance lock="+locked);
    }
    /** No enable path until durable settings and actual-use audit are implemented. */
    public static String requestLockBypassJson(Player player,boolean enabled,long durationMillis){
        Main main=instance;if(main==null||!ownsLand())return protectionReply("UNAVAILABLE",false,"LAND_NOT_ACTIVE",List.of(),List.of());
        main.requirePrimaryThread();
        if(player==null||!player.isOnline()||!player.hasPermission("tokaland.admin")||!player.hasPermission("tokaland.lock-bypass"))
            return protectionReply("DENY",false,"MISSING_PERMISSION",List.of(),List.of());
        return protectionReply("UNAVAILABLE",false,"LOCK_BYPASS_AUDIT_UNAVAILABLE",List.of(),List.of());
    }
    private boolean externalAuthority(Player player,LandClaim claim,ClaimPermission action){
        ProtectedAuthority provider=protectedAuthority;if(provider==null)return false;
        var plugin=getServer().getPluginManager().getPlugin(provider.pluginName());
        if(plugin!=provider.pluginIdentity()||!plugin.isEnabled()){protectedAuthority=null;return false;}
        JsonObject request=new JsonObject();request.add("claim",DatasetRepository.encode(claim));request.addProperty("action",action.name());
        try{return provider.callback().test(player,request.toString());}
        catch(RuntimeException|LinkageError failure){protectedAuthority=null;getLogger().warning("Land 外部保護授權失效，維持拒絕："+failure.getClass().getSimpleName());return false;}
    }
    private void requirePrimaryThread(){if(!getServer().isPrimaryThread())throw new IllegalStateException("Land protection API requires server thread");}
    private static String protectionReply(String status,boolean allowed,String reason,List<UUID> hits,List<UUID> blocked){
        JsonObject reply=new JsonObject();reply.addProperty("status",status);reply.addProperty("allowed",allowed);reply.addProperty("reason",reason);
        JsonArray all=new JsonArray();hits.forEach(id->all.add(id.toString()));reply.add("claims",all);
        JsonArray denied=new JsonArray();blocked.forEach(id->denied.add(id.toString()));reply.add("blockingClaims",denied);return reply.toString();
    }
    private boolean legacyOwnership(){
        var zero=getServer().getPluginManager().getPlugin("zero");if(zero==null||!zero.isEnabled())return false;
        try{return (Boolean)zero.getClass().getMethod("ownsLegacyLand").invoke(zero);}
        catch(NoSuchMethodException missing){try{return (Boolean)zero.getClass().getMethod("isModuleActive",String.class).invoke(zero,"land")||(Boolean)zero.getClass().getMethod("isModuleActive",String.class).invoke(zero,"area");}catch(Exception|LinkageError error){return true;}}
        catch(Exception|LinkageError error){return true;}
    }
    public List<LandClaim> findAllAt(String world, int x, int y, int z) { return lands.snapshot().index().findAllAt(world, x, y, z); }
    public Optional<LandClaim> getClaim(UUID id) { return lands.snapshot().index().get(id); }
    public Collection<LandClaim> allClaims(){return lands.snapshot().index().all();}
    public String language(cn.nukkit.command.CommandSender sender){return sender instanceof Player player?player.getLocale().toLanguageTag():getConfig().getString("language","zh-TW");}
    public String text(cn.nukkit.command.CommandSender sender,String key,Object...args){return message(language(sender),key,args);}
    public void sendSelectionPoint(Player player,boolean first,dev.toka.land.protection.SelectionToolListener.Point point){
        player.sendMessage(text(player,first?"selection.first":"selection.second",point.world(),point.x(),point.y(),point.z()));
    }
    public String ownerDisplay(LandClaim claim,String language){return ownerText(claim,language);}
    public static String exportDatasetJson(){
        Main main=instance;if(main==null||!main.isReady())throw new IllegalStateException("Land unavailable");
        var snapshot=main.lands.snapshot();var out=new JsonObject();out.addProperty("format",1);out.addProperty("revision",snapshot.revision());
        var rows=new JsonArray();snapshot.index().all().stream().sorted(Comparator.comparing(c->c.id().toString())).forEach(c->rows.add(DatasetRepository.encode(c)));out.add("claims",rows);return out.toString();
    }
    /** General dataset replacement for offline administration and other plugins; no legacy format parsing. */
    public static java.util.concurrent.CompletableFuture<String> replaceDatasetJson(String expectedRevision,String json){
        Main main=instance;if(main==null||!main.isReady())throw new IllegalStateException("Land unavailable");
        if(main.ownership)throw new IllegalStateException("Bulk replacement requires inactive ownership");
        var envelope=JsonParser.parseString(json).getAsJsonObject();DatasetRepository.requireFormatOne(envelope.get("format"));
        for(String field:envelope.keySet())if(!Set.of("format","claims","revision").contains(field))throw new IllegalArgumentException("Unsupported dataset field: "+field);
        List<LandClaim> claims=new ArrayList<>();for(var row:envelope.getAsJsonArray("claims"))claims.add(DatasetRepository.decode(row.getAsJsonObject()));
        return main.lands.replace(expectedRevision,claims).thenApply(revision->exportDatasetJson());
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { updateTopbar(event.getPlayer()); }

    @EventHandler public void onMove(PlayerMoveEvent event) {
        Position from = event.getFrom(), to = event.getTo();
        if (from.getLevel() != to.getLevel() || from.getFloorX() != to.getFloorX()
                || from.getFloorY() != to.getFloorY() || from.getFloorZ() != to.getFloorZ())
            updateTopbar(event.getPlayer());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { removeTopbar(event.getPlayer()); }

    private void refreshTopbars() {
        for (Player player : getServer().getOnlinePlayers().values()) updateTopbar(player, true);
    }

    private void updateTopbar(Player player) { updateTopbar(player, false); }

    private void updateTopbar(Player player, boolean refresh) {
        if (!ownsLand() || !getConfig().getBoolean("display.player-land-topbar", true)) return;
        String language=player.getLocale().toLanguageTag();
        List<String> names = findAllAt(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ())
                .stream().filter(claim -> claim.kind() == ClaimKind.PLAYER)
                .map(claim->message(language,"ui.land",claim.displayName(language),ownerText(claim,language))).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (names.isEmpty()) { removeTopbar(player); return; }
        String text = String.join("\n", names);
        UUID playerId = player.getUniqueId();
        if (!refresh && text.equals(shownClaims.get(playerId))) return;
        shownClaims.put(playerId, text);
        if (displayBossBar != null) {
            try { displayBossBar.invoke(null, player, "tokaLand:claim", text, 100f, 20, 40); return; }
            catch (ReflectiveOperationException | LinkageError error) { getLogger().warning("tokaDisplay API 失效，改用 Nukkit 頂部顯示：" + error.getClass().getSimpleName()); displayBossBar = null; }
        }
        Long id = fallbackBars.get(playerId);
        DummyBossBar bar = id == null ? null : player.getDummyBossBar(id);
        if (bar == null) {
            bar = new DummyBossBar.Builder(player).text(text).length(100f).build();
            fallbackBars.put(playerId, player.createBossBar(bar));
        } else if (!text.equals(bar.getText())) bar.setText(text);
    }

    private String message(String language,String key,Object...args){
        String selected=language!=null&&language.startsWith("en")?"en-US":"zh-TW";
        return languages.computeIfAbsent(selected,tag->new Messages(getClass().getClassLoader(),getDataFolder().toPath(),tag,line->getLogger().warning(line))).text(key,args);
    }
    private String ownerText(LandClaim claim,String language){
        var owner=ClaimAccess.effectiveOwner(lands.snapshot().index(),claim.id());
        if(owner.isPresent()){
            String fallback=claim.ownerNameSnapshot()==null?owner.get().toString():claim.ownerNameSnapshot();
            return playerNames.find(owner.get()).orElse(fallback);
        }
        if(claim.legacyOwnerName()!=null)return message(language,"ui.pending",claim.legacyOwnerName());
        return message(language,claim.kind()==ClaimKind.PLAYER?"ui.inheritedOwner":"ui.publicOwner");
    }

    private void removeTopbar(Player player) {
        UUID playerId = player.getUniqueId();
        shownClaims.remove(playerId);
        if (displayRemove != null) try { displayRemove.invoke(null, player, "tokaLand:claim"); }
        catch (ReflectiveOperationException | LinkageError ignored) { }
        Long id = fallbackBars.remove(playerId);
        if (id != null) try { player.removeBossBar(id); } catch (RuntimeException | LinkageError ignored) { }
    }

    private void connectDisplay() {
        Plugin plugin = getServer().getPluginManager().getPlugin("tokaDisplay");
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            Class<?> api = Class.forName("dev.toka.display.Main", true, plugin.getClass().getClassLoader());
            java.lang.reflect.Method bossBar = api.getMethod("bossBar", Player.class, String.class, String.class,
                    float.class, int.class, int.class);
            java.lang.reflect.Method remove = api.getMethod("remove", Player.class, String.class);
            displayBossBar = bossBar;
            displayRemove = remove;
        } catch (ReflectiveOperationException | LinkageError error) {
            getLogger().warning("tokaDisplay API 不相容，將使用 Nukkit 頂部顯示：" + error.getClass().getSimpleName());
        }
    }
}
