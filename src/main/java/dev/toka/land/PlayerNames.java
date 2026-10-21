package dev.toka.land;
import java.util.*;
import cn.nukkit.Server;

/** Display-only memory lookups. No name-to-UUID grants and no filesystem lookup in event handlers. */
final class PlayerNames {
    private final Server server;
    private record Cached(Optional<String> name,long until){}
    private final Map<UUID,Cached> cache=new LinkedHashMap<>();
    PlayerNames(Server server){this.server=server;}
    Optional<String> find(UUID id){
        var online=server.getPlayer(id);if(online.isPresent())return Optional.of(online.get().getName());
        long now=System.nanoTime();var cached=cache.get(id);if(cached!=null&&cached.until()>now)return cached.name();
        Optional<String> name=Optional.empty();var plugin=server.getPluginManager().getPlugin("tokaProfiles");
        if(plugin!=null&&plugin.isEnabled())try{
            Object repository=plugin.getClass().getMethod("profiles").invoke(plugin);
            Object found=repository.getClass().getMethod("find",UUID.class).invoke(repository,id);
            if(found instanceof Optional<?> profile&&profile.isPresent())name=Optional.of((String)profile.get().getClass().getMethod("name").invoke(profile.get()));
        }catch(ReflectiveOperationException|LinkageError ignored){}
        if(cache.size()>=4096)cache.remove(cache.keySet().iterator().next());cache.put(id,new Cached(name,now+5_000_000_000L));return name;
    }
}
