package dev.toka.land.protection;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityPrimedTNT;
import cn.nukkit.entity.projectile.EntityProjectile;
import java.util.UUID;

/** Live, main-thread evidence adapter. Core source fields are not a durable ownership journal. */
public final class ExplosionAttribution {
    private ExplosionAttribution(){}
    private static final ActorAttribution.Evidence<Entity> EVIDENCE=new ActorAttribution.Evidence<>() {
        public ActorAttribution.Kind kind(Entity entity){
            if(entity instanceof Player)return ActorAttribution.Kind.PLAYER;
            if(entity instanceof EntityPrimedTNT)return ActorAttribution.Kind.TNT;
            if(entity instanceof EntityProjectile)return ActorAttribution.Kind.PROJECTILE;
            return ActorAttribution.Kind.UNKNOWN;
        }
        public UUID playerId(Entity entity){return entity instanceof Player player?player.getUniqueId():null;}
        public Entity source(Entity entity){
            if(entity instanceof EntityPrimedTNT tnt)return tnt.getSource();
            if(entity instanceof EntityProjectile projectile)return projectile.shootingEntity;
            return null;
        }
    };
    public static ActorAttribution.Result resolve(Entity origin,int maxDepth){return ActorAttribution.resolve(origin,EVIDENCE,maxDepth);}
}
