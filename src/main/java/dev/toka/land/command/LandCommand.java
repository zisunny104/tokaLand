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
import java.util.*;

/** Legacy user commands are enabled only after verified ownership; inspection remains available in standby. */
public final class LandCommand extends Command {
    private final Main plugin;
    private final ClaimTools tools;
    public LandCommand(Main plugin) {
        super("land", "Land 領地管理", "/land help");
        this.plugin=plugin;
        this.tools=new ClaimTools(plugin);
        setPermission("tokaland.use;tokaland.inspect");
        getCommandParameters().clear();
        addCommandParameters("summary",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandReadAction",new String[]{"help","status"}))});
        addCommandParameters("set",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandSetAction",new String[]{"set"})),CommandParameter.newType("name",CommandParamType.STRING)});
        addCommandParameters("tpl",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandTplAction",new String[]{"tpl"})),CommandParameter.newType("claim",true,CommandParamType.STRING)});
        for(String action:List.of("rename","give","type"))addCommandParameters(action,new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLand"+action,new String[]{action})),
            action.equals("type")?CommandParameter.newEnum("value",new CommandEnum("TokaLandType",new String[]{"land","pvp"})):CommandParameter.newType("value",CommandParamType.STRING),CommandParameter.newType("claim",true,CommandParamType.STRING)});
        addCommandParameters("groupEdit",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandGroup",new String[]{"group","grp"})),CommandParameter.newEnum("mode",new CommandEnum("TokaLandGroupEdit",new String[]{"add","del"})),CommandParameter.newType("player",CommandParamType.STRING),CommandParameter.newType("claim",true,CommandParamType.STRING)});
        addCommandParameters("groupList",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandGroupListAction",new String[]{"group","grp"})),CommandParameter.newEnum("mode",new CommandEnum("TokaLandGroupList",new String[]{"list"})),CommandParameter.newType("claim",true,CommandParamType.STRING)});
        addCommandParameters("del",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandDeleteAction",new String[]{"del"})),CommandParameter.newType("claim",true,CommandParamType.STRING)});
        addCommandParameters("select",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandSelectAction",new String[]{"select","slt"})),CommandParameter.newEnum("mode",new CommandEnum("TokaLandSelectMode",new String[]{"size","vert"}))});
        addCommandParameters("list",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandListAction",new String[]{"list"})),CommandParameter.newType("page",true,CommandParamType.INT)});
        addCommandParameters("facility",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandFacilityAction",new String[]{"fac","facility"})),
            CommandParameter.newEnum("type",new CommandEnum("TokaLandFacilityType",plugin.facilities().types().names().toArray(new String[0]))),CommandParameter.newType("name",true,CommandParamType.STRING)});
        addCommandParameters("localInfo",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandLocalInfoAction",new String[]{"info"}))});
        addCommandParameters("ownerList",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandOwnerListAction",new String[]{"list"})),CommandParameter.newType("player",true,CommandParamType.TARGET)});
        addCommandParameters("reset",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandResetAction",new String[]{"reset"})),CommandParameter.newType("claim",true,CommandParamType.STRING)});
        addCommandParameters("clear",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandClearAction",new String[]{"clear"})),CommandParameter.newEnum("step",true,new CommandEnum("TokaLandClearStep",new String[]{"confirm"}))});
        addCommandParameters("info",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandInfoAction",new String[]{"info"})),CommandParameter.newType("claimId",CommandParamType.STRING)});
    }
    @Override public boolean execute(CommandSender sender,String label,String[] args) {
        if(!testPermissionSilent(sender)){send(sender,"command.denied");return true;}
        String action=args.length==0?"help":args[0].toLowerCase(Locale.ROOT);
        if(action.equals("help")&&args.length<=1){
            if(sender.hasPermission("tokaland.inspect"))for(String key:List.of("command.helpStatus","command.helpList","command.helpInfo"))send(sender,key);
            if(sender.hasPermission("tokaland.use"))for(String key:List.of("purchase.helpSet","purchase.helpTpl","purchase.helpSelect"))send(sender,key);
            if(sender.hasPermission("tokaland.use"))for(String key:List.of("tools.helpInfo","tools.helpList"))send(sender,key);
            if(sender.hasPermission("tokaland.admin"))for(String key:List.of("tools.helpReset","tools.helpClear"))send(sender,key);
            if(sender.hasPermission("tokaland.admin"))send(sender,"facility.help");
            if(sender.hasPermission("tokaland.use"))for(String key:List.of("management.helpRename","management.helpGive","management.helpType","management.helpGroup","management.helpDel"))send(sender,key);
            return true;
        }
        if(!plugin.isReady()){send(sender,"ui.unavailable","Land");return true;}
        if(Set.of("rename","give","type","group","grp","del").contains(action)
                ||action.equals("tpl")&&sender instanceof Player p&&(args.length>1||!plugin.management().claimsAround(p).isEmpty()))return manage(sender,action,args);
        if(action.equals("fac")||action.equals("facility"))return facilityCommand(sender,args);
        if(Set.of("set","tpl","select","slt").contains(action))return purchaseCommand(sender,action,args);
        if(!sender.hasPermission("tokaland.inspect")){send(sender,"command.denied");return true;}
        if(sender instanceof Player self&&sender.hasPermission("tokaland.use")){
            if(action.equals("info")&&args.length==1)return tools.localInfo(self);
            if(action.equals("list")&&args.length==2&&!args[1].chars().allMatch(Character::isDigit))return tools.ownerList(sender,args[1]);
            if(action.equals("list")&&args.length==1&&!sender.hasPermission("tokaland.inspect"))return tools.ownerList(sender,self.getName());
            if(action.equals("reset")||action.equals("clear"))return adminTool(self,action,args);
        }
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
            send(sender,"command.info",claim.id(),claim.displayName(plugin.texts().language(sender)),plugin.texts().ownerText(claim,plugin.texts().language(sender)),claim.world(),claim.kind(),claim.type(),claim.accessPolicy().mode()+(claim.accessPolicy().locked()?" + LOCK":""),claim.selections().size());
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
                    var part=draft.geometry();long size=Math.multiplyExact((long)part.maxX()-part.minX()+1,(long)part.maxZ()-part.minZ()+1);
                    if(!part.ignoreHeight())size=Math.multiplyExact(size,(long)part.maxY()-part.minY()+1);
                    send(sender,"purchase.size",size);return true;
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
            if(args.length>2){send(player,"command.usage");return true;}
            var target=ClaimTargets.resolve(plugin.management().claimsAround(player),args.length==2?args[1]:null);
            plugin.management().check(player,target);
            return tools.reset(player,target);
        }catch(ClaimTargets.Ambiguous ambiguous){
            send(player,"management.ambiguous");
            return true;
        }catch(RuntimeException failure){send(player,"purchase.unavailable",failure.getMessage());return true;}
    }
    private boolean facilityCommand(CommandSender sender,String[] args){
        if(!(sender instanceof Player player)||!sender.hasPermission("tokaland.admin")){send(sender,"command.denied");return true;}
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
    private void send(CommandSender sender,String key,Object...args){sender.sendMessage(plugin.texts().text(sender,key,args));}
    private boolean manage(CommandSender sender,String action,String[] args){
        if(!(sender instanceof Player player)||!sender.hasPermission("tokaland.use")){send(sender,"command.denied");return true;}
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
                plugin.trading().sell(player,before).whenComplete((result,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                    if(error!=null)send(sender,"purchase.unavailable",error.getClass().getSimpleName());
                    else send(sender,"management.saleResult",result.status(),result.operation(),result.detail());
                }));send(sender,"management.saleStarted");return true;
            }
            LandClaim after;
            switch(action){
                case "rename" -> after=ClaimEdits.rename(before,args[1]);
                case "give" -> {UUID person=plugin.management().resolvePlayer(args[1]);after=ClaimEdits.owner(before,person,plugin.management().playerName(person));}
                case "type" -> after=ClaimEdits.type(before,args[1].toLowerCase(Locale.ROOT));
                case "tpl" -> {var at=player.getLocation();after=ClaimEdits.teleport(before,new dev.toka.land.model.ClaimDetails.TeleportPoint(at.getLevel().getFolderName(),at.x,at.y,at.z,(float)at.yaw,(float)at.pitch));}
                case "group","grp" -> {
                    String mode=args[1].toLowerCase(Locale.ROOT);
                    if(mode.equals("list")){
                        send(sender,"management.groupHeader",before.id(),before.name());
                        before.accessGrants().stream().map(g->g.subject()).distinct().forEach(subject->send(sender,"management.member",subject.type(),subject.type()==AccessSubject.Type.PLAYER_UUID?plugin.management().playerName(UUID.fromString(subject.id())):subject.id(),subject.id()));
                        return true;
                    }
                    if(!Set.of("add","del").contains(mode))throw new IllegalArgumentException("INVALID_GROUP_ACTION");
                    List<AccessSubject> legacy=before.accessGrants().stream().map(g->g.subject()).filter(s->s.type()==AccessSubject.Type.LEGACY_NAME&&s.id().equalsIgnoreCase(args[2])).distinct().toList();
                    UUID resolved=null;
                    try{resolved=plugin.management().resolvePlayer(args[2]);}catch(IllegalArgumentException unresolved){if(mode.equals("add")||legacy.isEmpty())throw unresolved;}
                    after=before;
                    if(resolved!=null)after=ClaimEdits.member(after,AccessSubject.player(resolved),mode.equals("add"));
                    if(mode.equals("del"))for(var subject:legacy)after=ClaimEdits.member(after,subject,false);
                }
                default -> throw new IllegalArgumentException("UNKNOWN_ACTION");
            }
            plugin.management().edit(player,before,after).whenComplete((revision,error)->plugin.getServer().getScheduler().scheduleTask(plugin,()->{
                if(error==null)send(sender,"management.saved",before.id());
                else {Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();send(sender,"purchase.unavailable",cause.getMessage());}
            }));
        }catch(ClaimTargets.Ambiguous ambiguous){
            send(sender,"management.ambiguous");for(var match:ambiguous.matches())send(sender,"management.target",match.name(),match.id());
        }catch(RuntimeException failure){send(sender,"purchase.unavailable",failure.getMessage());}
        return true;
    }
}
