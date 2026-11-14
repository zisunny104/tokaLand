package dev.toka.land.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.*;
import dev.toka.land.Main;
import dev.toka.land.model.AccessPolicy;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.FacilityTypes;
import java.util.*;

public final class FacilityCommand extends Command {
    private final Main plugin;
    private final HelpPages help;

    public FacilityCommand(Main plugin) {
        super("fac", "設施管理", "/fac help");
        this.plugin = plugin;
        setPermission("tokaland.admin");
        help = new HelpPages("fac", List.of(new HelpPages.Section("facility", "tokaland.admin", false,
                List.of("facCreate", "facList", "facShow", "facInfo", "facSelect", "facExpand", "facDel", "facType", "facMode", "facLock"))),
                (sender, key, args) -> plugin.texts().text(sender, key, args));
        Map<String, CommandParameter[]> parameters = new HashMap<>();
        Set<String> actions = new TreeSet<>(FacilityTypes.SUBCOMMANDS);
        actions.addAll(plugin.facilities().types().names());
        parameters.put("action", new CommandParameter[]{
                CommandParameter.newEnum("action", new CommandEnum("TokaFacAction", actions.toArray(String[]::new))),
                CommandParameter.newType("arguments", true, CommandParamType.RAWTEXT)});
        parameters.put("select-coordinates", SelectionCommand.parameters("TokaFac"));
        parameters.put("expand-coordinates", SelectionCommand.expandParameters("TokaFac"));
        parameters.put("expand-default", SelectionCommand.expandParameters("TokaFac", false));
        parameters.put("select-default", SelectionCommand.defaultSelectionParameters("TokaFac"));
        setCommandParameters(parameters);
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("expand")) return SelectionCommand.expand(plugin, sender, args, ClaimKind.FACILITY);
        if (!(sender instanceof Player player) || !sender.hasPermission("tokaland.admin")) {
            send(sender, "command.denied");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help.show(player, Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("select")) return SelectionCommand.execute(plugin, player, args, "select.coordinatesFac");
        if (FacilityTypes.SUBCOMMANDS.contains(action)) return facilityMaintenance(player, args);
        if(args.length<1||args.length>2){help.showTopic(player,"facility");return true;}
        try{
            String type=args[0].toLowerCase(Locale.ROOT),name=args.length==2?args[1]:null;
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
    private boolean facilityMaintenance(Player player,String[] args){
        var facilities=plugin.facilities();
        try{
            switch(args[0].toLowerCase(Locale.ROOT)){
                case "list" -> {
                    if (args.length > 2) { help.showTopic(player, "facility"); return true; }
                    var all=facilities.list();int page=1;
                    if(args.length==2)page=Integer.parseInt(args[1]);
                    int pages=all.isEmpty()?1:(all.size()-1)/10+1;
                    if(page<1||page>pages){send(player,"command.invalidPage");return true;}
                    send(player,"facility.listHeader",page,pages,all.size());
                    for(int i=(page-1)*10;i<Math.min(page*10,all.size());i++)send(player,"facility.listRow",all.get(i).id(),all.get(i).name(),all.get(i).type(),all.get(i).accessPolicy().mode()+(all.get(i).accessPolicy().locked()?"+LOCK":""));
                }
                case "del" -> {
                    if(args.length<2||args.length>3){send(player,"command.usage");return true;}
                    var target=facilities.find(args[1]);
                    // Deleting is permanent and unrefunded, so it needs an explicit confirm after naming the target.
                    if(args.length==2){send(player,"facility.deleteConfirm",target.name(),target.id());return true;}
                    if(!args[2].equalsIgnoreCase("confirm")){send(player,"command.usage");return true;}
                    facilities.delete(player,target.id().toString()).whenComplete((r,e)->done(player,e,"facility.deleted",target.name()));
                }
                case "type" -> {
                    if(args.length!=3){send(player,"command.usage");return true;}
                    facilities.retype(player,args[1],args[2]).whenComplete((r,e)->done(player,e,"facility.retyped",args[1],args[2]));
                }
                case "mode" -> {
                    if(args.length!=3){send(player,"command.usage");return true;}
                    var mode=switch(args[2].toLowerCase(Locale.ROOT)){case "open"->AccessPolicy.Mode.OPEN;case "protect"->AccessPolicy.Mode.PROTECT;case "private"->AccessPolicy.Mode.PRIVATE;default->null;};
                    if(mode==null){send(player,"command.usage");return true;}
                    facilities.mode(player,args[1],mode).whenComplete((r,e)->done(player,e,"facility.moded",args[1],mode));
                }
                case "lock" -> {
                    if(args.length<2||args.length>3){send(player,"command.usage");return true;}
                    boolean on=args.length==2||args[2].equalsIgnoreCase("on");
                    if(args.length==3&&!on&&!args[2].equalsIgnoreCase("off")){send(player,"command.usage");return true;}
                    facilities.lock(player,args[1],on).whenComplete((r,e)->done(player,e,on?"facility.locked":"facility.unlocked",args[1]));
                }
                case "show", "info" -> showOrInfo(player, args);
                default -> send(player,"command.usage");
            }
        }catch(NumberFormatException invalid){send(player,"command.invalidPage");}
        catch(IllegalArgumentException|IllegalStateException failure){send(player,"purchase.unavailable",failure.getMessage());}
        return true;
    }

    private void showOrInfo(Player player, String[] args) {
        if (args.length > 2) {
            help.showTopic(player, "facility");
            return;
        }
        var claims = args.length == 2 ? List.of(plugin.facilities().find(args[1]))
                : plugin.findAllAt(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ())
                        .stream().filter(claim -> claim.kind() == ClaimKind.FACILITY).toList();
        if (claims.isEmpty()) {
            send(player, "tools.noneHere");
            return;
        }
        if (args[0].equalsIgnoreCase("show")) {
            if (!dev.toka.land.BoundingBoxLink.available(plugin)) {
                send(player, "tools.boundsMissing");
                return;
            }
            claims.forEach(claim -> plugin.window().showBounds(player, claim));
        } else {
            String language = plugin.texts().language(player);
            claims.forEach(claim -> player.sendMessage(plugin.texts().claimInfo(claim, language)));
        }
    }

    private void send(CommandSender sender, String key, Object... args) {
        sender.sendMessage(plugin.texts().text(sender, key, args));
    }

    private void done(Player player, Throwable error, String key, Object... args) {
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            if (error == null) send(player, key, args);
            else {
                Throwable cause = error;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                send(player, "purchase.unavailable", cause.getMessage());
            }
        });
    }
}
