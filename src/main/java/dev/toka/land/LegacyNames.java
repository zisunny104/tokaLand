package dev.toka.land;

import dev.toka.land.model.*;
import java.util.*;

/**
 * Replaces old names by accounts, only where the profile service has said a name now means an account. Nothing here decides who an
 * old name is; a name that has not been announced stays as it is, and a legacy name never authorizes anything.
 */
final class LegacyNames {
    private LegacyNames() {}

    static String key(String name) {
        String text = name.replaceAll("\\p{Cntrl}", "").strip();
        if (text.startsWith("@")) text = text.substring(1);
        return text.replaceAll("^['\"]+|['\"]+$", "").strip().toLowerCase(Locale.ROOT);
    }

    /** The claims with their old names replaced; the same list when nothing changed. */
    static List<LandClaim> apply(List<LandClaim> claims, Map<String, UUID> resolved) {
        Map<String, UUID> byKey = new HashMap<>();
        resolved.forEach((name, uuid) -> byKey.put(key(name), uuid));
        boolean changed = false;
        List<LandClaim> out = new ArrayList<>();
        for (LandClaim claim : claims) {
            LandClaim next = replace(claim, byKey);
            changed |= next != claim;
            out.add(next);
        }
        return changed ? out : claims;
    }

    private static LandClaim replace(LandClaim claim, Map<String, UUID> byKey) {
        UUID owner = claim.legacyOwnerName() == null ? null : byKey.get(key(claim.legacyOwnerName()));
        List<AccessGrant> grants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean grantsChanged = false;
        for (AccessGrant grant : claim.accessGrants()) {
            AccessGrant use = grant;
            if (grant.subject().type() == AccessSubject.Type.LEGACY_NAME && byKey.containsKey(key(grant.subject().id()))) {
                use = new AccessGrant(AccessSubject.player(byKey.get(key(grant.subject().id()))), grant.permission(), grant.decision());
                grantsChanged = true;
            }
            if (seen.add(use.subject() + "/" + use.permission())) grants.add(use); else grantsChanged = true;
        }
        if (owner == null && !grantsChanged) return claim;
        return new LandClaim(claim.id(), claim.name(), claim.kind(), claim.type(), owner != null ? owner : claim.ownerId(), claim.parentClaimId(),
                claim.inheritOwner(), claim.inheritPermissions(), claim.selections(), grants, claim.accessPolicy(), claim.localizedNames(),
                owner != null ? null : claim.legacyOwnerName(), owner != null ? claim.legacyOwnerName() : claim.ownerNameSnapshot(), claim.details());
    }
}
