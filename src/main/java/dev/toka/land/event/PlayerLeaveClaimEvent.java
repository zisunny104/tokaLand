package dev.toka.land.event;

import cn.nukkit.Player;
import cn.nukkit.event.HandlerList;
import cn.nukkit.level.Position;
import java.util.UUID;

/** Fired once per claim a player steps out of, including on quit or when the claim is gone. */
public final class PlayerLeaveClaimEvent extends PlayerClaimEvent {
    private static final HandlerList handlers = new HandlerList();

    public PlayerLeaveClaimEvent(Player player, UUID claimId, String claimName, String kind, String type, Position at) {
        super(player, claimId, claimName, kind, type, at);
    }

    public static HandlerList getHandlers() { return handlers; }
}
