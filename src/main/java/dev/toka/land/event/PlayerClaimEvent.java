package dev.toka.land.event;

import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerEvent;
import cn.nukkit.level.Position;
import java.util.UUID;

/** A player crossed a claim boundary. Carries plain values so listeners need no Land classes. */
public abstract class PlayerClaimEvent extends PlayerEvent {
    private final UUID claimId;
    private final String claimName;
    private final String kind;
    private final String type;
    private final Position at;

    protected PlayerClaimEvent(Player player, UUID claimId, String claimName, String kind, String type, Position at) {
        this.player = player;
        this.claimId = claimId;
        this.claimName = claimName;
        this.kind = kind;
        this.type = type;
        this.at = at;
    }

    // API: getters read by other plugins' listeners (plain values, no Land classes)
    public UUID getClaimId() { return claimId; }
    public String getClaimName() { return claimName; }
    /** PLAYER, PUBLIC or FACILITY. */
    public String getKind() { return kind; }
    /** The claim's type, for example land, pvp, road or rail. */
    public String getType() { return type; }
    /** Where the player is (or is going) at this moment; the player's own position may not be updated yet. */
    public Position getAt() { return at; }
}
