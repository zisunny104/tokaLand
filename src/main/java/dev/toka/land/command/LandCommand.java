package dev.toka.land.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.*;
import dev.toka.land.Main;
import dev.toka.land.model.LandClaim;
import java.util.*;

/** Read-only administration remains available while native ownership is in standby. */
public final class LandCommand extends Command {
    private final Main plugin;
    public LandCommand(Main plugin) {
        super("tokaland", "Land 資料查詢", "/tokaland help");
        this.plugin=plugin;
        setPermission("tokaland.inspect");
        getCommandParameters().clear();
        addCommandParameters("summary",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandReadAction",new String[]{"help","status"}))});
        addCommandParameters("list",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandListAction",new String[]{"list"})),CommandParameter.newType("page",true,CommandParamType.INT)});
        addCommandParameters("info",new CommandParameter[]{CommandParameter.newEnum("action",new CommandEnum("TokaLandInfoAction",new String[]{"info"})),CommandParameter.newType("claimId",CommandParamType.STRING)});
    }
    @Override public boolean execute(CommandSender sender,String label,String[] args) {
        if(!testPermissionSilent(sender)){send(sender,"command.denied");return true;}
        String action=args.length==0?"help":args[0].toLowerCase(Locale.ROOT);
        if(action.equals("help")&&args.length<=1){
            for(String key:List.of("command.helpStatus","command.helpList","command.helpInfo"))send(sender,key);
            return true;
        }
        if(!plugin.isReady()){send(sender,"ui.unavailable","Land");return true;}
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
                LandClaim claim=claims.get(i);send(sender,"command.listRow",claim.id(),claim.displayName(plugin.language(sender)),claim.kind());
            }
            return true;
        }
        if(action.equals("info")&&args.length==2){
            UUID id;try{id=UUID.fromString(args[1]);if(!id.toString().equalsIgnoreCase(args[1]))throw new IllegalArgumentException();}
            catch(IllegalArgumentException invalid){send(sender,"command.invalidId");return true;}
            var found=plugin.getClaim(id);
            if(found.isEmpty()){send(sender,"command.notFound",id);return true;}
            var claim=found.get();
            send(sender,"command.info",claim.id(),claim.displayName(plugin.language(sender)),plugin.ownerDisplay(claim,plugin.language(sender)),claim.world(),claim.kind(),claim.type(),claim.details().protection(),claim.selections().size());
            for(var part:claim.selections())send(sender,"command.selection",part.id(),part.minX(),part.minY(),part.minZ(),part.maxX(),part.maxY(),part.maxZ(),part.ignoreHeight());
            return true;
        }
        send(sender,"command.usage");return true;
    }
    private void send(CommandSender sender,String key,Object...args){sender.sendMessage(plugin.text(sender,key,args));}
}
