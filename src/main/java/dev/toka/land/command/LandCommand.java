package dev.toka.land.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.*;
import cn.nukkit.Player;
import dev.toka.land.Main;
import dev.toka.land.model.LandClaim;
import dev.toka.land.model.ClaimEdits;
import dev.toka.land.model.ClaimTargets;
import dev.toka.land.model.AccessSubject;
import dev.toka.land.model.AccessPolicy;
import dev.toka.land.model.ClaimAccess;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.MemberLevel;
import dev.toka.land.model.TeleportAccess;
import java.util.*;

/** Legacy user commands are enabled only after verified ownership; inspection remains available in standby. */
public final class LandCommand extends Command {
    private final Main plugin;
    private final ClaimTools tools;
    private final HelpPages help;
    public LandCommand(Main plugin) {
        super("land", "Land 領地管理", "/land help");
        this.plugin=plugin;
        this.tools=new ClaimTools(plugin);
        this.help=new HelpPages("land",List.of(
            new HelpPages.Section("basics","tokaland.use",false,List.of("menu","info","show","list","select","set","tpl","rename","del")),
            new HelpPages.Section("manage","tokaland.use",true,List.of("give","type","group","leave","tpaccess","note")),
            new HelpPages.Section("sub","tokaland.use",true,List.of("sub","leaseMenu","leaseOffer","leaseSet","leaseCancel","leaseRent","leaseRelease","leaseInfo","leaseList")),
            new HelpPages.Section("auction","tokaland.use",true,List.of("auctionMenu","auctionStart","auctionBid","auctionCancel","auctionList","auctionInfo")),
            new HelpPages.Section("admin","tokaland.admin",true,List.of("grant","refund","reset","clear","reload","fac","facHelp")),
            new HelpPages.Section("facility","tokaland.admin",true,List.of("facCreate","facList","facDel","facType","facMode","facLock")),
            new HelpPages.Section("inspect","tokaland.inspect",true,List.of("status","adminList","adminInfo"))),(sender,key,args)->plugin.texts().text(sender,key,args));
        setPermission("tokaland.use;tokaland.inspect");
        getCommandParameters().clear();
        // One line in the usage list: the word, then the rest as free text. The words that follow are described by /land help.
        addCommandParameters("action",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandAction",new String[]{"help","menu","info","show","list","select","set","tpl","rename","del","give","type","group","leave","tpaccess","sub","lease","auction","fac","status","grant","refund","reset","clear","reload"})),
            CommandParameter.newType("arguments",true,CommandParamType.RAWTEXT)});
    }
    /** The claims the player is a member of are offered after "leave"; the free-text overload stays for names the client cannot list. */
    @Override public CommandDataVersions generateCustomCommandData(Player player){
        var data=super.generateCustomCommandData(player);
        if(data==null||!plugin.isReady())return data;
        var names=plugin.index().memberOf(player.getUniqueId()).stream().map(LandClaim::name).filter(name->name.equals(name.toLowerCase(Locale.ROOT))).distinct().sorted().toList();
        if(names.isEmpty())return data;
        for(CommandData version:data.versions){
            var overload=new CommandOverload();
            overload.input.parameters=new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandLeaveAction",new String[]{"leave"})),CommandParameter.newEnum("claim",new CommandEnum("TokaLandMemberClaims",names))};
            version.overloads.put("leaveMember",overload);
        }
        return data;
    }
    @Override public boolean execute(CommandSender sender,String label,String[] args) {
        if(!testPermissionSilent(sender)){send(sender,"command.denied");return true;}
        String action=args.length==0?"help":args[0].toLowerCase(Locale.ROOT);
        if(action.equals("help")){help.show(sender,args.length>1?java.util.Arrays.copyOfRange(args,1,args.length):new String[0]);return true;}
        if(action.equals("reload")&&args.length==1){
            if(!sender.hasPermission("tokaland.admin")){send(sender,"command.denied");return true;}
            plugin.reloadSettings();send(sender,"command.reloaded");return true;
        }
        if(!plugin.isReady()){send(sender,"ui.unavailable","Land");return true;}
        if(Set.of("rename","give","type","group","grp","del").contains(action)
                ||action.equals("tpl")&&sender instanceof Player p&&(args.length>1||!plugin.management().claimsAround(p).isEmpty()))return manage(sender,action,args);
        if(action.equals("leave"))return leave(sender,args);
        if(action.equals("tpaccess"))return tpAccess(sender,args);
        if(action.equals("sub")||action.equals("lease"))return plugin.leases().command(sender,args);
        if(action.equals("auction"))return plugin.auctions().command(sender,args);
        if(action.equals("fac")||action.equals("facility"))return facilityCommand(sender,args);
        if(Set.of("set","tpl","select","slt").contains(action))return purchaseCommand(sender,action,args);
        if(sender instanceof Player self&&sender.hasPermission("tokaland.use")){
            if((action.equals("menu")||action.equals("gui"))&&args.length==1){plugin.window().open(self);return true;}
            if(action.equals("info")&&args.length==1)return tools.localInfo(self);
            if(action.equals("show")&&args.length==1)return tools.showHere(self);
            if(action.equals("list")&&args.length==2&&!args[1].chars().allMatch(Character::isDigit))return tools.ownerList(sender,args[1]);
            if(action.equals("list")&&args.length==1&&!sender.hasPermission("tokaland.inspect"))return tools.ownerList(sender,self.getName());
            if(Set.of("reset","clear","grant","refund").contains(action))return adminTool(self,action,args);
        }
        if(!sender.hasPermission("tokaland.inspect")){send(sender,"command.denied");return true;}
        if(action.equals("status")&&args.length==1){send(sender,"command.status",Main.ownsLand()?"ACTIVE":"STANDBY",plugin.allClaims().size());return true;}
        if(action.equals("list")&&args.length<=2){
            int page=1;
            try{if(args.length==2)page=Integer.parseInt(args[1]);if(page<1)throw new NumberFormatException();}
            catch(NumberFormatException invalid){send(sender,"command.invalidPage");return true;}
            List<LandClaim> claims=plugin.allClaims().stream().sorted(Comparator.comparing(c->c.id().toString())).toList();
            int pages=claims.isEmpty()?1:(claims.size()-1)/10+1;
            if(page>pages){send(sender,"command.invalidPage");return true;}
            send(sender,"command.listHeader",page,pages,claims.size());
            for(int i=(page-1)*10;i<Math.min((long)page*10,claims.size());i++){
                LandClaim claim=claims.get(i);send(sender,"command.listRow",claim.id(),claim.displayName(plugin.texts().language(sender)),claim.kind());
            }
            return true;
        }
        if(action.equals("info")&&args.length==2){
            UUID id;try{id=UUID.fromString(args[1]);if(!id.toString().equalsIgnoreCase(args[1]))throw new IllegalArgumentException();}
            catch(IllegalArgumentException invalid){send(sender,"command.invalidId");return true;}
            var found=plugin.getClaim(id);
            if(found.isEmpty()){send(sender,"command.notFound",id);return true;}
            var claim=found.get();
            sender.sendMessage(plugin.texts().claimInfo(claim,plugin.texts().language(sender)));
            for(var part:claim.selections())send(sender,"command.selection",part.id(),part.minX(),part.minY(),part.minZ(),part.maxX(),part.maxY(),part.maxZ(),part.ignoreHeight());
            return true;
        }
        send(sender,"command.usage");return true;
    }
    private boolean purchaseCommand(CommandSender sender,String action,String[] args){
        if(!(sender instanceof Player player)||!sender.hasPermission("tokaland.use")){send(sender,"command.denied");return true;}
        if(!Main.ownsLand()){send(sender,"purchase.unavailable","LAND_NOT_ACTIVE");return true;}
        var tool=plugin.selectionTool();if(tool==null){send(sender,"purchase.unavailable","SELECTION_TOOL_DISABLED");return true;}
        try{
            if(action.equals("tpl")&&args.length==1){
                var at=player.getLocation();tool.teleport(player.getUniqueId(),new dev.toka.land.model.ClaimDetails.TeleportPoint(at.getLevel().getFolderName(),at.x,at.y,at.z,(float)at.yaw,(float)at.pitch));
                send(sender,"purchase.teleportSet");return true;
            }
            if((action.equals("select")||action.equals("slt"))&&args.length==2){
                var draft=tool.get(player.getUniqueId());if(draft==null)throw new IllegalStateException("SELECTION_REQUIRED");
                if(args[1].equalsIgnoreCase("vert")){draft=tool.toggleHeight(player.getUniqueId());send(sender,"purchase.height",draft.ignoreHeight());return true;}
                if(args[1].equalsIgnoreCase("size")){
                    tool.announce(player,draft);return true;
                }
            }
            if(action.equals("set")&&args.length>=2){
                // Retain the old name parsing until any command-format change is explicitly agreed.
                StringBuilder name=new StringBuilder(args[1]);for(int i=2;i<args.length;i++)name.append(args[i]).append('_');
                var selected=tool.get(player.getUniqueId());
                var work=plugin.trading().purchase(player,name.toString());send(sender,"purchase.started");
                work.whenComplete((result,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                    if(error!=null){send(sender,"purchase.unavailable",error.getClass().getSimpleName());return;}
                    if(result.status().equals("COMPLETED"))tool.forget(player.getUniqueId(),selected);
                    send(sender,"purchase.result",result.status(),result.operation(),result.detail());
                }));return true;
            }
        }catch(RuntimeException failure){send(sender,"purchase.unavailable",failure.getMessage());return true;}
        send(sender,"command.usage");return true;
    }
    private boolean adminTool(Player player,String action,String[] args){
        if(!player.hasPermission("tokaland.admin")){send(player,"command.denied");return true;}
        try{
            if(action.equals("clear")){
                if(args.length>2||args.length==2&&!args[1].equalsIgnoreCase("confirm")){send(player,"command.usage");return true;}
                return tools.clear(player,args.length==2);
            }
            if(action.equals("grant")){
                if(args.length!=3){send(player,"command.usage");return true;}
                UUID owner=plugin.management().resolvePlayer(args[1]);
                plugin.management().grant(player,owner,args[2]).whenComplete((name,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                    if(error!=null)send(player,"purchase.unavailable",rootMessage(error));else send(player,"grant.done",args[1],name);
                }));
                return true;
            }
            if(action.equals("refund")){
                if(args.length<2||args.length>3){send(player,"command.usage");return true;}
                long amount=Long.parseLong(args[1]);
                var claim=ClaimTargets.resolve(plugin.management().claimsAround(player),args.length==3?args[2]:null);
                plugin.management().setRefund(player,claim,amount).whenComplete((revision,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                    if(error!=null)send(player,"purchase.unavailable",rootMessage(error));else send(player,"refund.done",claim.name(),amount);
                }));
                return true;
            }
            if(args.length>2){send(player,"command.usage");return true;}
            var target=ClaimTargets.resolve(plugin.management().claimsAround(player),args.length==2?args[1]:null);
            plugin.management().check(player,target);
            return tools.reset(player,target);
        }catch(ClaimTargets.Ambiguous ambiguous){
            send(player,"management.ambiguous");
            return true;
        }catch(RuntimeException failure){send(player,"purchase.unavailable",failure.getMessage());return true;}
    }
    private boolean facilityMaintenance(Player player,String[] args){
        var facilities=plugin.facilities();
        try{
            switch(args[1].toLowerCase(Locale.ROOT)){
                case "list" -> {
                    var all=facilities.list();int page=1;
                    if(args.length==3)page=Integer.parseInt(args[2]);
                    int pages=all.isEmpty()?1:(all.size()-1)/10+1;
                    if(page<1||page>pages){send(player,"command.invalidPage");return true;}
                    send(player,"facility.listHeader",page,pages,all.size());
                    for(int i=(page-1)*10;i<Math.min(page*10,all.size());i++)send(player,"facility.listRow",all.get(i).id(),all.get(i).name(),all.get(i).type(),all.get(i).accessPolicy().mode()+(all.get(i).accessPolicy().locked()?"+LOCK":""));
                }
                case "del" -> {
                    if(args.length<3||args.length>4){send(player,"command.usage");return true;}
                    var target=facilities.find(args[2]);
                    // Deleting is permanent and unrefunded, so it needs an explicit confirm after naming the target.
                    if(args.length==3){send(player,"facility.deleteConfirm",target.name(),target.id());return true;}
                    if(!args[3].equalsIgnoreCase("confirm")){send(player,"command.usage");return true;}
                    facilities.delete(player,target.id().toString()).whenComplete((r,e)->done(player,e,"facility.deleted",target.name()));
                }
                case "type" -> {
                    if(args.length!=4){send(player,"command.usage");return true;}
                    facilities.retype(player,args[2],args[3]).whenComplete((r,e)->done(player,e,"facility.retyped",args[2],args[3]));
                }
                case "mode" -> {
                    if(args.length!=4){send(player,"command.usage");return true;}
                    var mode=switch(args[3].toLowerCase(Locale.ROOT)){case "open"->AccessPolicy.Mode.OPEN;case "protect"->AccessPolicy.Mode.PROTECT;case "private"->AccessPolicy.Mode.PRIVATE;default->null;};
                    if(mode==null){send(player,"command.usage");return true;}
                    facilities.mode(player,args[2],mode).whenComplete((r,e)->done(player,e,"facility.moded",args[2],mode));
                }
                case "lock" -> {
                    if(args.length<3||args.length>4){send(player,"command.usage");return true;}
                    boolean on=args.length==3||args[3].equalsIgnoreCase("on");
                    if(args.length==4&&!on&&!args[3].equalsIgnoreCase("off")){send(player,"command.usage");return true;}
                    facilities.lock(player,args[2],on).whenComplete((r,e)->done(player,e,on?"facility.locked":"facility.unlocked",args[2]));
                }
                case "help" -> help.showTopic(player,"facility");
                default -> send(player,"command.usage");
            }
        }catch(NumberFormatException invalid){send(player,"command.invalidPage");}
        catch(IllegalArgumentException|IllegalStateException failure){send(player,"purchase.unavailable",failure.getMessage());}
        return true;
    }
    private static String rootMessage(Throwable error){
        Throwable root=error;while(root instanceof java.util.concurrent.CompletionException&&root.getCause()!=null)root=root.getCause();
        return root.getMessage()==null?root.getClass().getSimpleName():root.getMessage();
    }
    private void done(Player player,Throwable error,String key,Object...args){
        plugin.getServer().getScheduler().scheduleTask(plugin,()->{
            if(error==null)send(player,key,args);
            else{Throwable root=error;while(root instanceof java.util.concurrent.CompletionException&&root.getCause()!=null)root=root.getCause();
                send(player,"purchase.unavailable",root.getMessage()==null?root.getClass().getSimpleName():root.getMessage());}
        });
    }
    private boolean facilityCommand(CommandSender sender,String[] args){
        if(!(sender instanceof Player player)||!sender.hasPermission("tokaland.admin")){send(sender,"command.denied");return true;}
        if(args.length>=2&&dev.toka.land.model.FacilityTypes.SUBCOMMANDS.contains(args[1].toLowerCase(Locale.ROOT)))return facilityMaintenance(player,args);
        if(args.length<2||args.length>3){send(sender,"command.usage");return true;}
        try{
            String type=args[1].toLowerCase(Locale.ROOT),name=args.length==3?args[2]:null;
            plugin.facilities().save(player,type,name).whenComplete((status,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                if(error!=null)send(sender,"purchase.unavailable",error.getClass().getSimpleName());
                else send(sender,status.equals("CREATED")?"facility.created":"facility.extended",name==null?type:name,type);
            }));
        }catch(IllegalArgumentException unknown){
            if("UNKNOWN_FACILITY_TYPE".equals(unknown.getMessage()))send(sender,"facility.unknownType",String.join(", ",plugin.facilities().types().names()));
            else send(sender,"purchase.unavailable",unknown.getMessage());
        }catch(IllegalStateException failure){send(sender,"purchase.unavailable",failure.getMessage());}
        return true;
    }
    /** Whether the query names a claim the player owns (or rents); only then "leave" is answered with the owner message. */
    private boolean owns(UUID player,String query){
        try{ClaimTargets.resolve(plugin.index().ownedBy(player),query);return true;}catch(IllegalArgumentException none){return false;}
    }
    private boolean leave(CommandSender sender,String[] args){
        if(!(sender instanceof Player player)||!sender.hasPermission("tokaland.use")){send(sender,"command.denied");return true;}
        if(args.length!=2){send(sender,"command.usage");return true;}
        UUID me=player.getUniqueId();
        try{
            if(owns(me,args[1])){send(sender,"leave.owner");return true;}
            LandClaim claim=ClaimTargets.resolve(plugin.index().memberOf(me),args[1]);
            plugin.management().leave(player,claim).whenComplete((revision,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                if(error!=null){leaveFailed(sender,rootMessage(error));return;}
                send(sender,"leave.done",claim.name());
                player.sendCommandData();
                ClaimAccess.effectiveOwner(plugin.index(),claim.id()).flatMap(id->plugin.getServer().getPlayer(id))
                        .ifPresent(owner->owner.sendMessage(plugin.texts().text(owner,"leave.ownerTold",player.getName(),claim.name())));
            }));
        }catch(ClaimTargets.Ambiguous ambiguous){
            send(sender,"management.ambiguous");for(var match:ambiguous.matches())send(sender,"management.target",match.name(),match.id());
        }catch(RuntimeException failure){leaveFailed(sender,String.valueOf(failure.getMessage()));}
        return true;
    }
    private void leaveFailed(CommandSender sender,String code){
        switch(code){
            case "LEAVE_OWNER" -> send(sender,"leave.owner");
            case "LEAVE_VIA_PARENT" -> send(sender,"leave.viaParent");
            case "NOT_MEMBER","CLAIM_NOT_FOUND" -> send(sender,"leave.notMember");
            default -> send(sender,"purchase.unavailable",code);
        }
    }
    /** /land tpaccess &lt;claim&gt; owner|member|all: the claim's owner (or an administrator) chooses who may use its teleport point. */
    private boolean tpAccess(CommandSender sender,String[] args){
        if(!(sender instanceof Player player)||!sender.hasPermission("tokaland.use")){send(sender,"command.denied");return true;}
        var access=args.length==3?TeleportAccess.parse(args[2]):Optional.<TeleportAccess>empty();
        if(access.isEmpty()){send(sender,"command.usage");return true;}
        try{
            // The claims under the player's feet, like the other editing commands, and the ones they own, so the window can name any of them.
            Map<UUID,LandClaim> candidates=new LinkedHashMap<>();
            plugin.management().claimsAround(player).forEach(claim->candidates.put(claim.id(),claim));
            plugin.index().ownedBy(player.getUniqueId()).stream().filter(claim->claim.kind()==ClaimKind.PLAYER).forEach(claim->candidates.putIfAbsent(claim.id(),claim));
            LandClaim before=ClaimTargets.resolve(candidates.values(),args[1]);
            plugin.management().check(player,before);
            plugin.management().edit(player,before,ClaimEdits.teleportAccess(before,access.get())).whenComplete((revision,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                if(error==null)send(sender,"tpaccess.done",before.name(),plugin.texts().text(sender,"window.tpaccess."+access.get().name()));
                else send(sender,"purchase.unavailable",rootMessage(error));
            }));
        }catch(ClaimTargets.Ambiguous ambiguous){
            send(sender,"management.ambiguous");for(var match:ambiguous.matches())send(sender,"management.target",match.name(),match.id());
        }catch(RuntimeException failure){send(sender,"purchase.unavailable",failure.getMessage());}
        return true;
    }
    /** Tells an added member in chat, if online; nothing is stored for a player who is away. */
    private void tellAdded(Player adder,LandClaim claim,UUID who,MemberLevel level){
        plugin.getServer().getPlayer(who).ifPresent(target->{
            target.sendMessage(plugin.texts().text(target,"management.added",claim.displayName(plugin.texts().language(target)),adder.getName(),plugin.texts().text(target,"window.level."+level.name()),claim.name()));
            target.sendCommandData();
        });
    }
    private void send(CommandSender sender,String key,Object...args){sender.sendMessage(plugin.texts().text(sender,key,args));}
    private boolean manage(CommandSender sender,String action,String[] given){
        if(!(sender instanceof Player player)||!sender.hasPermission("tokaland.use")){send(sender,"command.denied");return true;}
        // "norefund" accepts deleting without a refund when the treasury cannot cover it.
        boolean noRefund=action.equals("del")&&Arrays.stream(given).skip(1).anyMatch(a->a.equalsIgnoreCase("norefund"));
        final String[] args=noRefund?Arrays.stream(given).filter(a->!a.equalsIgnoreCase("norefund")).toArray(String[]::new):given;
        try{
            String target=null;
            if(action.equals("tpl")||action.equals("del")){if(args.length>2){send(sender,"command.usage");return true;}if(args.length==2)target=args[1];}
            else if(action.equals("group")||action.equals("grp")){
                boolean list=args.length>=2&&args[1].equalsIgnoreCase("list");int required=list?2:3;
                if(args.length<required||args.length>required+1){send(sender,"command.usage");return true;}
                if(args.length==required+1)target=args[required];
            }else{if(args.length<2||args.length>3){send(sender,"command.usage");return true;}if(args.length==3)target=args[2];}
            LandClaim before=ClaimTargets.resolve(plugin.management().claimsAround(player),target);plugin.management().check(player,before);
            if(action.equals("del")){
                plugin.trading().sell(player,before,noRefund).whenComplete((result,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                    if(error!=null)send(sender,"purchase.unavailable",error.getClass().getSimpleName());
                    else send(sender,"management.saleResult",result.status(),result.operation(),result.detail());
                }));send(sender,"management.saleStarted");return true;
            }
            LandClaim after;
            UUID notify=null;MemberLevel notifyLevel=null;
            switch(action){
                case "rename" -> after=ClaimEdits.rename(before,args[1]);
                case "give" -> {UUID person=plugin.management().resolvePlayer(args[1]);after=ClaimEdits.owner(before,person,plugin.management().playerName(person));}
                case "type" -> after=ClaimEdits.type(before,args[1].toLowerCase(Locale.ROOT));
                case "tpl" -> {var at=player.getLocation();after=ClaimEdits.teleport(before,new dev.toka.land.model.ClaimDetails.TeleportPoint(at.getLevel().getFolderName(),at.x,at.y,at.z,(float)at.yaw,(float)at.pitch));}
                case "group","grp" -> {
                    String mode=args[1].toLowerCase(Locale.ROOT);
                    if(mode.equals("list")){
                        send(sender,"management.groupHeader",before.id(),before.name());
                        before.accessGrants().stream().map(g->g.subject()).distinct().forEach(subject->send(sender,"management.member",subject.type(),subject.type()==AccessSubject.Type.PLAYER_UUID?plugin.management().playerName(UUID.fromString(subject.id())):subject.id(),subject.id(),
                            subject.type()==AccessSubject.Type.PLAYER_UUID&&MemberLevel.of(before,subject)!=null?plugin.texts().text(sender,"window.level."+MemberLevel.of(before,subject).name()):""));
                        return true;
                    }
                    if(!Set.of("add","visitor","del").contains(mode))throw new IllegalArgumentException("INVALID_GROUP_ACTION");
                    List<AccessSubject> legacy=before.accessGrants().stream().map(g->g.subject()).filter(s->s.type()==AccessSubject.Type.LEGACY_NAME&&s.id().equalsIgnoreCase(args[2])).distinct().toList();
                    UUID resolved=null;
                    try{resolved=plugin.management().resolvePlayer(args[2]);}catch(IllegalArgumentException unresolved){if(mode.equals("add")||legacy.isEmpty())throw unresolved;}
                    after=before;
                    MemberLevel level=mode.equals("add")?MemberLevel.MEMBER:mode.equals("visitor")?MemberLevel.VISITOR:null;
                    if(resolved!=null)after=ClaimEdits.member(after,AccessSubject.player(resolved),level);
                    if(resolved!=null&&level!=null&&!resolved.equals(player.getUniqueId())&&MemberLevel.of(before,AccessSubject.player(resolved))!=level){notify=resolved;notifyLevel=level;}
                    if(mode.equals("del"))for(var subject:legacy)after=ClaimEdits.member(after,subject,false);
                }
                default -> throw new IllegalArgumentException("UNKNOWN_ACTION");
            }
            final UUID told=notify;final MemberLevel toldLevel=notifyLevel;
            plugin.management().edit(player,before,after).whenComplete((revision,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                if(error==null){send(sender,"management.saved",before.id());if(told!=null)tellAdded(player,before,told,toldLevel);}
                else {Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();send(sender,"purchase.unavailable",cause.getMessage());}
            }));
        }catch(ClaimTargets.Ambiguous ambiguous){
            send(sender,"management.ambiguous");for(var match:ambiguous.matches())send(sender,"management.target",match.name(),match.id());
        }catch(RuntimeException failure){send(sender,"TREASURY_INSUFFICIENT".equals(failure.getMessage())?"management.treasuryShort":"purchase.unavailable",failure.getMessage());}
        return true;
    }
}
