package dev.toka.land.protection;

import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.LandClaim;
import java.util.List;

/** PvP claims let player-versus-player damage through server-wide protection. */
public final class PvpPolicy {
    public static final String TYPE = "pvp";
    private PvpPolicy() {}

    /** A spot is a PvP zone only when every player claim covering it is of PvP type. */
    static boolean zone(List<LandClaim> hits) {
        boolean found = false;
        for (LandClaim claim : hits) {
            if (claim.kind() != ClaimKind.PLAYER) continue;
            if (!TYPE.equals(claim.type())) return false;
            found = true;
        }
        return found;
    }

    /** Both fighters must stand in a PvP zone, so nobody is pulled into a fight from outside. */
    public static boolean allows(List<LandClaim> victimHits, List<LandClaim> attackerHits) {
        return zone(victimHits) && zone(attackerHits);
    }
}
