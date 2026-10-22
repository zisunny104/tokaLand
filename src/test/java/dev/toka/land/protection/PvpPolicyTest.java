package dev.toka.land.protection;

import dev.toka.land.model.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PvpPolicyTest {
    private static LandClaim claim(ClaimKind kind, String type) {
        return new LandClaim(UUID.randomUUID(), "c", kind, type, UUID.randomUUID(), null, false, true,
                List.of(new LandSelection("w", 0, 0, 0, 5, 5, 5, false)), List.of(), AccessPolicy.privateDefault(),
                Map.of(), null, null, ClaimDetails.privateDefaults());
    }

    @Test public void bothFightersInPvpClaimsAreAllowed() {
        var pvp = List.of(claim(ClaimKind.PLAYER, "pvp"));
        assertTrue(PvpPolicy.allows(pvp, pvp));
    }

    @Test public void fighterOutsideOrInOrdinaryLandIsNotAllowed() {
        var pvp = List.of(claim(ClaimKind.PLAYER, "pvp"));
        assertFalse(PvpPolicy.allows(pvp, List.of()));
        assertFalse(PvpPolicy.allows(pvp, List.of(claim(ClaimKind.PLAYER, "land"))));
    }

    @Test public void ordinaryClaimOverlappingPvpClaimBlocksPvp() {
        assertFalse(PvpPolicy.zone(List.of(claim(ClaimKind.PLAYER, "pvp"), claim(ClaimKind.PLAYER, "land"))));
    }

    @Test public void facilityClaimsAreIgnoredButCannotMakeAZone() {
        assertTrue(PvpPolicy.zone(List.of(claim(ClaimKind.PLAYER, "pvp"), claim(ClaimKind.FACILITY, "road"))));
        assertFalse(PvpPolicy.zone(List.of(claim(ClaimKind.FACILITY, "road"))));
    }
}
