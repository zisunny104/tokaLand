package dev.toka.land.model;

import java.util.*;

/** Resolves sparse child overrides without copying the parent's member list into each child. */
public final class ClaimAccess {
    private ClaimAccess() {}

    public static Optional<UUID> effectiveOwner(LandIndex index, UUID claimId) {
        Set<UUID> visited = new HashSet<>();
        LandClaim current = index.get(claimId).orElse(null);
        while (current != null && visited.add(current.id())) {
            if (current.ownerId() != null) return Optional.of(current.ownerId());
            if (!current.inheritOwner() || current.parentClaimId() == null) return Optional.empty();
            current = index.get(current.parentClaimId()).orElse(null);
        }
        return Optional.empty();
    }

    public static boolean allows(LandIndex index, UUID claimId, UUID subject,
                                 ClaimPermission permission, boolean fallback) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(permission, "permission");
        Set<UUID> visited = new HashSet<>();
        LandClaim current = index.get(claimId).orElse(null);
        while (current != null && visited.add(current.id())) {
            if (effectiveOwner(index, current.id()).filter(subject::equals).isPresent()) return true;
            AccessDecision decision = Optional.ofNullable(current.accessOverrides().get(subject))
                    .map(entries -> entries.get(permission)).orElse(null);
            if (decision != null) return decision == AccessDecision.ALLOW;
            if (!current.inheritPermissions() || current.parentClaimId() == null) break;
            current = index.get(current.parentClaimId()).orElse(null);
        }
        return fallback;
    }
}
