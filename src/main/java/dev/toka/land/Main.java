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
import dev.toka.land.model.AccessPolicy;
import dev.toka.land.model.AccessSubject;
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
    private dev.toka.land.purchase.PurchaseCoordinator purchases;
    private dev.toka.land.purchase.SaleCoordinator sales;
    private volatile dev.toka.land.purchase.EconomyLedger economyLedger;
    private Plugin economyPlugin;
    private record PurchasePolicy(Plugin plugin,java.util.function.BiFunction<Player,String,String> callback){}
    private PurchasePolicy purchasePolicy;
    private final java.util.concurrent.atomic.AtomicBoolean recoveringPurchases=new java.util.concurrent.atomic.AtomicBoolean();
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
    private record AccessProvider(String pluginName,Plugin pluginIdentity,java.util.function.BiPredicate<Player,String> callback){}
    private final Map<String,AccessProvider> accessProviders=new HashMap<>();

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
            purchases=new dev.toka.land.purchase.PurchaseCoordinator(lands,store,()->economyLedger);
            sales=new dev.toka.land.purchase.SaleCoordinator(lands,store,()->economyLedger);
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
                var configuredTool=cn.nukkit.item.Item.fromString(getConfig().getString("selection.tool","290:0"));
                if(configuredTool.getId()==0)getLogger().warning("選地工具設定無效，已停用選區點擊；請檢查 selection.tool。");
                else{
                    selectionToolListener=new dev.toka.land.protection.SelectionToolListener(this,configuredTool);
                    getServer().getPluginManager().registerEvents(selectionToolListener,this);
                }
            }
            ready = true;
            connectEconomy();
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
            getServer().getScheduler().scheduleRepeatingTask(this,()->{
                connectEconomy();
                recoverPurchases();
            },200);
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
        if(economyLedger!=null)economyLedger.disconnect();
        if(sales!=null)try{sales.close();}catch(IOException error){getLogger().error("售地交易仍在保存，保留儲存鎖。",error);return;}
        if(purchases!=null)try{purchases.close();}catch(IOException error){getLogger().error("購地交易仍在保存，保留儲存鎖。",error);return;}
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
            if(claim.accessPolicy().locked())return false;
            if(administrator)return true;
            if(claim.accessPolicy().mode()==AccessPolicy.Mode.OPEN)return true;
            return player!=null&&(ClaimAccess.allows(index,claim.id(),attribution.playerId(),ClaimPermission.BUILD,false,
                    subject->subjectMatches(player,subject))
                    ||claim.accessPolicy().mode()==AccessPolicy.Mode.PROTECT&&externalAuthority(player,claim,ClaimPermission.BUILD));
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
        var result=ProtectionDecision.evaluate(index,hits,actor,permission,false,(claim,requested)->main.externalAuthority(player,claim,requested),subject->main.subjectMatches(player,subject));
        String reason=result.reason();
        if(!result.allowed()&&hits.stream().anyMatch(c->result.blockingClaims().contains(c.id())&&c.accessPolicy().locked()))
            reason="LOCK_BYPASS_AUDIT_UNAVAILABLE";
        return protectionReply(result.allowed()?"ALLOW":"DENY",result.allowed(),reason,hits.stream().map(LandClaim::id).toList(),result.blockingClaims());
    }
    /** Main-thread, owner-only list for the player's /tpl menu. */
    public static String queryTeleportTargetsJson(Player player){
        Main main=instance;
        if(main==null||!main.isReady())return teleportReply("UNAVAILABLE",new JsonArray());
        main.requirePrimaryThread();
        if(player==null||!player.isOnline())return teleportReply("INVALID_REQUEST",new JsonArray());
        var index=main.lands.snapshot().index();
        var targets=index.all().stream()
                .filter(claim->claim.kind()==ClaimKind.PLAYER&&claim.details().teleport()!=null)
                .filter(claim->ClaimAccess.effectiveOwner(index,claim.id()).filter(player.getUniqueId()::equals).isPresent())
                .sorted(Comparator.comparing(LandClaim::name,String.CASE_INSENSITIVE_ORDER).thenComparing(claim->claim.id().toString()))
                .toList();
        JsonArray rows=new JsonArray();targets.forEach(claim->rows.add(teleportTarget(claim)));
        JsonObject reply=new JsonObject();reply.addProperty("status","READY");reply.add("targets",rows);return reply.toString();
    }
    /** Stable UUID lookup; names are accepted only when unique among targets the player may use. */
    public static String queryTeleportTargetJson(Player player,String idOrName){
        Main main=instance;
        if(main==null||!main.isReady())return teleportReply("UNAVAILABLE",new JsonArray());
        main.requirePrimaryThread();
        if(player==null||!player.isOnline()||idOrName==null||idOrName.isBlank())return teleportReply("INVALID_REQUEST",new JsonArray());
        var index=main.lands.snapshot().index();
        List<LandClaim> accessible=index.all().stream()
                .filter(claim->claim.kind()==ClaimKind.PLAYER&&claim.details().teleport()!=null)
                .filter(claim->claim.details().publicTeleport()
                        ||ClaimAccess.allows(index,claim.id(),player.getUniqueId(),ClaimPermission.INTERACT,false))
                .toList();
        LandClaim selected=null;
        try{
            UUID id=UUID.fromString(idOrName.strip());
            var candidate=index.get(id).orElse(null);
            if(candidate!=null){
                if(!accessible.contains(candidate))return teleportReply("DENIED",new JsonArray());
                selected=candidate;
            }
        }catch(IllegalArgumentException ignored){}
        if(selected==null){
            String requested=idOrName.strip();
            List<LandClaim> matches=accessible.stream().filter(claim->claim.name().equalsIgnoreCase(requested)).toList();
            if(matches.size()>1)return teleportReply("AMBIGUOUS",new JsonArray());
            if(matches.isEmpty())return teleportReply("NOT_FOUND",new JsonArray());
            selected=matches.getFirst();
        }
        JsonArray rows=new JsonArray();rows.add(teleportTarget(selected));return teleportReply("OK",rows);
    }
    /** Public, minimal label lookup for a configured sign; exposes no coordinates or permission data. */
    public static String queryTeleportDisplayJson(String idOrName){
        Main main=instance;
        if(main==null||!main.isReady())return teleportReply("UNAVAILABLE",new JsonArray());
        main.requirePrimaryThread();
        if(idOrName==null||idOrName.isBlank())return teleportReply("INVALID_REQUEST",new JsonArray());
        var index=main.lands.snapshot().index();
        List<LandClaim> targets=index.all().stream()
                .filter(claim->claim.kind()==ClaimKind.PLAYER&&claim.details().teleport()!=null).toList();
        LandClaim selected=null;
        try{selected=index.get(UUID.fromString(idOrName.strip())).orElse(null);}
        catch(IllegalArgumentException ignored){}
        if(selected!=null&&!targets.contains(selected))selected=null;
        if(selected==null){
            List<LandClaim> matches=targets.stream().filter(claim->claim.name().equalsIgnoreCase(idOrName.strip())).toList();
            if(matches.size()>1)return teleportReply("AMBIGUOUS",new JsonArray());
            if(matches.isEmpty())return teleportReply("NOT_FOUND",new JsonArray());
            selected=matches.getFirst();
        }
        JsonObject row=new JsonObject();row.addProperty("id",selected.id().toString());row.addProperty("name",selected.name());
        JsonArray rows=new JsonArray();rows.add(row);return teleportReply("OK",rows);
    }
    private static JsonObject teleportTarget(LandClaim claim){
        var point=claim.details().teleport();JsonObject row=new JsonObject();
        row.addProperty("id",claim.id().toString());row.addProperty("name",claim.name());row.addProperty("world",point.world());
        row.addProperty("x",point.x());row.addProperty("y",point.y());row.addProperty("z",point.z());
        row.addProperty("yaw",point.yaw());row.addProperty("pitch",point.pitch());return row;
    }
    private static String teleportReply(String status,JsonArray targets){
        JsonObject reply=new JsonObject();reply.addProperty("status",status);reply.add("targets",targets);return reply.toString();
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
    /** Registers a typed group resolver. Missing or failed providers never grant access. */
    public static void registerAccessSubjectProvider(String providerId,String pluginName,java.util.function.BiPredicate<Player,String> resolver){
        Main main=instance;if(main==null||!main.isReady())throw new IllegalStateException("Land unavailable");main.requirePrimaryThread();
        String key=Objects.requireNonNull(providerId).strip().toLowerCase(Locale.ROOT);
        if(key.isEmpty())throw new IllegalArgumentException("providerId is required");
        Plugin plugin=main.getServer().getPluginManager().getPlugin(Objects.requireNonNull(pluginName));
        if(plugin==null||!plugin.isEnabled())throw new IllegalArgumentException("Access provider plugin must be enabled");
        main.accessProviders.put(key,new AccessProvider(pluginName,plugin,Objects.requireNonNull(resolver)));
    }
    public static void unregisterAccessSubjectProvider(String providerId){
        Main main=instance;if(main==null)return;main.requirePrimaryThread();
        if(providerId!=null)main.accessProviders.remove(providerId.strip().toLowerCase(Locale.ROOT));
    }
    private boolean subjectMatches(Player player,AccessSubject subject){
        if(subject.type()==AccessSubject.Type.PLAYER_UUID)return subject.id().equals(player.getUniqueId().toString());
        if(subject.type()!=AccessSubject.Type.GROUP)return false;
        AccessProvider provider=accessProviders.get(subject.provider());if(provider==null)return false;
        Plugin current=getServer().getPluginManager().getPlugin(provider.pluginName());
        if(current!=provider.pluginIdentity()||!current.isEnabled()){accessProviders.remove(subject.provider());return false;}
        try{return provider.callback().test(player,subject.id());}
        catch(RuntimeException|LinkageError failure){accessProviders.remove(subject.provider());getLogger().warning("Land 群組授權服務失效，維持拒絕："+failure.getClass().getSimpleName());return false;}
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
        if(plugin==null||plugin!=provider.pluginIdentity()||!plugin.isEnabled()){protectedAuthority=null;return false;}
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
    public UUID resolvePlayer(String query){requirePrimaryThread();return playerNames.resolve(query);}
    public String playerName(UUID id){requirePrimaryThread();return playerNames.find(id).orElse(id.toString());}
    public List<LandClaim> localPlayerClaims(Player player){return findAllAt(player.getLevel().getFolderName(),player.getFloorX(),player.getFloorY(),player.getFloorZ()).stream().filter(c->c.kind()==ClaimKind.PLAYER).toList();}
    public java.util.concurrent.CompletableFuture<String> editClaim(Player player,LandClaim before,LandClaim after){
        checkManagement(player,before);return lands.edit(before,after);
    }
    public void checkManagement(Player player,LandClaim before){
        requirePrimaryThread();
        if(!ownsLand()||maintenanceLocked)throw new IllegalStateException("LAND_NOT_ACTIVE");
        if(purchases.hasPending(before.id())||sales.hasPending(before.id()))throw new IllegalStateException("TRANSACTION_RECOVERY_PENDING");
        if(before.kind()!=ClaimKind.PLAYER||before.accessPolicy().locked())throw new IllegalStateException("CLAIM_LOCKED");
        if(!player.hasPermission("tokaland.admin")&&!ClaimAccess.allows(lands.snapshot().index(),before.id(),player.getUniqueId(),ClaimPermission.MANAGE,false,subject->subjectMatches(player,subject)))
            throw new IllegalStateException("MANAGE_DENIED");
    }
    /** Generic quote contract. Legacy Region/tax extraction belongs to the provider, never to Land. */
    public static void registerPurchasePolicy(String pluginName,java.util.function.BiFunction<Player,String,String> callback){
        Main main=instance;if(main==null||!main.isReady())throw new IllegalStateException("Land unavailable");main.requirePrimaryThread();
        Plugin provider=main.getServer().getPluginManager().getPlugin(pluginName);
        if(provider==null||!provider.isEnabled())throw new IllegalArgumentException("Purchase policy provider unavailable");
        if(main.purchasePolicy!=null&&main.purchasePolicy.plugin()!=provider&&main.purchasePolicy.plugin().isEnabled())throw new IllegalStateException("Purchase policy already registered");
        main.purchasePolicy=new PurchasePolicy(provider,Objects.requireNonNull(callback));
    }
    private JsonObject purchasePolicy(Player player,JsonObject request){
        requirePrimaryThread();PurchasePolicy policy=purchasePolicy;
        if(policy==null||!policy.plugin().isEnabled()||getServer().getPluginManager().getPlugin(policy.plugin().getName())!=policy.plugin())
            throw new IllegalStateException("PURCHASE_POLICY_UNAVAILABLE");
        var reply=JsonParser.parseString(policy.callback().apply(player,request.toString())).getAsJsonObject();
        if(!"OK".equals(reply.get("status").getAsString()))throw new IllegalStateException("PURCHASE_POLICY_DENIED");
        return reply;
    }
    public boolean selectionIgnoreHeight(Player player,dev.toka.land.protection.SelectionToolListener.Point point){
        if(!ownsLand()||maintenanceLocked)throw new IllegalStateException("LAND_NOT_ACTIVE");
        if(!player.isOp()&&findAllAt(player.getLevel().getFolderName(),player.getFloorX(),player.getFloorY(),player.getFloorZ()).stream().anyMatch(c->c.kind()==ClaimKind.PLAYER))
            throw new IllegalStateException("SELECTION_INSIDE_LAND");
        JsonObject request=new JsonObject();request.addProperty("action","SELECT");request.add("point",new Gson().toJsonTree(point));
        return purchasePolicy(player,request).get("ignoreHeight").getAsBoolean();
    }
    public dev.toka.land.protection.SelectionToolListener selectionTool(){return selectionToolListener;}
    public java.util.concurrent.CompletableFuture<dev.toka.land.purchase.PurchaseCoordinator.Outcome> purchase(Player player,String name){
        requirePrimaryThread();if(!ownsLand()||maintenanceLocked)throw new IllegalStateException("LAND_NOT_ACTIVE");
        if(name.isBlank()||Set.of("help","list","set","del","edit").contains(name.strip().replaceAll("_+$","").toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("RESERVED_NAME");
        var draft=selectionToolListener==null?null:selectionToolListener.get(player.getUniqueId());
        if(draft==null)throw new IllegalStateException("SELECTION_REQUIRED");
        var part=draft.geometry();if(draft.teleport()==null)throw new IllegalStateException("TELEPORT_REQUIRED");
        UUID owner=player.getUniqueId();JsonObject request=new JsonObject();request.addProperty("action","QUOTE");
        request.add("first",new Gson().toJsonTree(draft.first()));request.add("second",new Gson().toJsonTree(draft.second()));
        request.addProperty("ignoreHeight",draft.ignoreHeight());
        request.addProperty("ownedCount",allClaims().stream().filter(c->ClaimAccess.effectiveOwner(lands.snapshot().index(),c.id()).filter(owner::equals).isPresent()).count());
        JsonObject quote=purchasePolicy(player,request);long amount=quote.get("amount").getAsBigDecimal().longValueExact();
        String currency=quote.get("currency").getAsString();
        LandClaim claim=new LandClaim(UUID.randomUUID(),name,ClaimKind.PLAYER,"land",owner,null,false,false,List.of(part),List.of(),
            AccessPolicy.privateDefault(),Map.of(),null,player.getName(),new ClaimDetails(owner,player.getName(),draft.teleport(),true));
        return purchases.purchase(claim,currency,amount);
    }
    public java.util.concurrent.CompletableFuture<dev.toka.land.purchase.PurchaseCoordinator.Outcome> sellClaim(Player player,LandClaim claim){
        checkManagement(player,claim);
        UUID creator=claim.details().creatorId();String creatorName=claim.details().creatorName();
        if(creator==null&&creatorName!=null&&!creatorName.isBlank())throw new IllegalStateException("CREATOR_IDENTITY_UNCONFIRMED");
        long amount=0;String currency="money";
        if(player.getUniqueId().equals(creator)){
            var request=new JsonObject();request.addProperty("action","REFUND");request.add("claim",DatasetRepository.encode(claim));
            var quote=purchasePolicy(player,request);currency=quote.get("currency").getAsString();amount=quote.get("amount").getAsBigDecimal().longValueExact();
        }
        return sales.sell(claim,player.getUniqueId(),currency,amount);
    }
    private void connectEconomy(){
        Plugin current=getServer().getPluginManager().getPlugin("tokaEconomy");
        if(current==economyPlugin&&current!=null&&current.isEnabled())return;
        if(economyLedger!=null)economyLedger.disconnect();economyLedger=null;economyPlugin=null;
        if(current==null||!current.isEnabled())return;
        try{economyLedger=new dev.toka.land.purchase.EconomyLedger(current);economyPlugin=current;}
        catch(ReflectiveOperationException|LinkageError failure){getLogger().warning("購地交易 API 尚未就緒："+failure.getClass().getSimpleName());}
    }
    private void recoverPurchases(){
        if(!ownsLand()||!recoveringPurchases.compareAndSet(false,true))return;
        var buying=purchases.recover();var selling=sales.recover();
        java.util.concurrent.CompletableFuture.allOf(buying,selling).whenComplete((ignored,failure)->{
            recoveringPurchases.set(false);
            if(failure!=null){getLogger().warning("領地交易恢復未完成："+failure.getClass().getSimpleName());return;}
            for(var result:buying.join())if(!result.status().equals("PENDING"))getLogger().info("購地交易恢復："+result.operation()+" "+result.status());
            for(var result:selling.join())if(!result.status().equals("PENDING"))getLogger().info("售地交易恢復："+result.operation()+" "+result.status());
        });
    }
    @EventHandler public void onPluginDisabled(cn.nukkit.event.plugin.PluginDisableEvent event){
        if(event.getPlugin()==economyPlugin&&economyLedger!=null){economyLedger.disconnect();economyLedger=null;economyPlugin=null;}
    }
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
        if(main.purchases.hasPending()||main.sales.hasPending())throw new IllegalStateException("Unfinished land transactions must be recovered before dataset replacement");
        var envelope=JsonParser.parseString(json).getAsJsonObject();DatasetRepository.requireSupportedFormat(envelope.get("format"));
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
