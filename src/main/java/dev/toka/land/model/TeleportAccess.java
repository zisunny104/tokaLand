package dev.toka.land.model;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Who may use a claim's teleport point. Stored on the claim as a word; a claim without one keeps what it always had:
 * public teleport means everybody, otherwise the members (anyone allowed to interact).
 */
public enum TeleportAccess {
    OWNER, MEMBERS, ALL;

    /** What the claim says, or the old behaviour when it says nothing. An unknown stored word is the strictest. */
    public static TeleportAccess of(ClaimDetails details) {
        if (details.teleportAccess() == null) return details.publicTeleport() ? ALL : MEMBERS;
        try { return valueOf(details.teleportAccess()); } catch (IllegalArgumentException unknown) { return OWNER; }
    }

    /** The word used in /land tpaccess. */
    public String word() { return this == MEMBERS ? "member" : name().toLowerCase(Locale.ROOT); }

    public static Optional<TeleportAccess> parse(String word) {
        if (word == null) return Optional.empty();
        return switch (word.strip().toLowerCase(Locale.ROOT)) {
            case "owner" -> Optional.of(OWNER);
            case "member", "members" -> Optional.of(MEMBERS);
            case "all" -> Optional.of(ALL);
            default -> Optional.empty();
        };
    }

    /** The one rule for every way to a claim's teleport point (other plugins and the window). Administrators always may. */
    public static boolean permits(LandIndex index, LandClaim claim, UUID player, boolean admin) {
        if (admin) return true;
        if (ClaimAccess.effectiveOwner(index, claim.id()).filter(player::equals).isPresent()) return true;
        return switch (of(claim.details())) {
            case OWNER -> false;
            case ALL -> true;
            case MEMBERS -> ClaimAccess.allows(index, claim.id(), player, ClaimPermission.INTERACT, false);
        };
    }
}
