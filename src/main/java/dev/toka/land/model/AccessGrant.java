package dev.toka.land.model;

import java.util.Objects;

/** One action-specific ACL entry. A grant is data only; provider resolution is supplied separately. */
public record AccessGrant(AccessSubject subject, ClaimPermission permission, AccessDecision decision) {
    public AccessGrant {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(decision, "decision");
    }
}
