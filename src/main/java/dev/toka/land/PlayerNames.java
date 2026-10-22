package dev.toka.land;
import java.util.*;
import cn.nukkit.Server;

/** Memory-only identity/display lookups. Names are resolved through live players or confirmed Profiles, never hashed into UUIDs. */
final class PlayerNames {
    private final Server server;
    private record Cached(Optional<String> name,long until){}
    private final Map<UUID,Cached> cache=new LinkedHashMap<>();
    PlayerNames(Server server){this.server=server;}
    /** Only live player identities or a unique Profiles mapping; no offline UUID generation or disk access. */
    UUID resolve(String query){
        Set<UUID> candidates=new HashSet<>();UUID explicit=null;
        try{UUID id=UUID.fromString(query);if(id.toString().equalsIgnoreCase(query))explicit=id;}catch(IllegalArgumentException ignored){}
        for(var online:server.getOnlinePlayers().values())if(explicit!=null?online.getUniqueId().equals(explicit):online.getName().equalsIgnoreCase(query))candidates.add(online.getUniqueId());
        var plugin=server.getPluginManager().getPlugin("tokaProfiles");
        if(plugin!=null&&plugin.isEnabled())try{
            Object repository=plugin.getClass().getMethod("profiles").invoke(plugin);
            if(explicit!=null){
                Object found=repository.getClass().getMethod("find",UUID.class).invoke(repository,explicit);
                if(found instanceof Optional<?> profile&&profile.isPresent())candidates.add(explicit);
            }else{
                Object found=repository.getClass().getMethod("resolveName",String.class).invoke(repository,query);
                if(!(found instanceof Set<?> values))throw new IllegalStateException("PROFILE_API_UNAVAILABLE");
                for(Object value:values)candidates.add((UUID)value);
            }
        }catch(ReflectiveOperationException|LinkageError failure){throw new IllegalStateException("PROFILE_API_UNAVAILABLE",failure);}
        if(candidates.size()!=1)throw new IllegalArgumentException(candidates.isEmpty()?"PLAYER_IDENTITY_UNCONFIRMED":"PLAYER_IDENTITY_CONFLICT");
        return candidates.iterator().next();
    }
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
