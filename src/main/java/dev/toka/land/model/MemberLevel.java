package dev.toka.land.model;

import java.util.EnumSet;
import java.util.Set;

/** A preset of the claim permissions given to a member; MEMBER is what members always got. Nothing but the grants is stored. */
public enum MemberLevel {
    VISITOR(EnumSet.of(ClaimPermission.INTERACT)),
    MEMBER(EnumSet.of(ClaimPermission.BUILD, ClaimPermission.INTERACT, ClaimPermission.CONTAINERS));

    /** Every permission a level may set; granting a level replaces exactly these. */
    public static final Set<ClaimPermission> MANAGED = EnumSet.of(ClaimPermission.BUILD, ClaimPermission.INTERACT, ClaimPermission.CONTAINERS);

    private final Set<ClaimPermission> permissions;

    MemberLevel(Set<ClaimPermission> permissions) { this.permissions = permissions; }

    public Set<ClaimPermission> permissions() { return permissions; }

    /** The level the claim's own grants give the subject; null when it has no explicit right to interact. Anything wider than a visitor counts as member. */
    public static MemberLevel of(LandClaim claim, AccessSubject subject) {
        Set<ClaimPermission> allowed = EnumSet.noneOf(ClaimPermission.class);
        for (AccessGrant grant : claim.accessGrants())
            if (grant.subject().equals(subject) && grant.decision() == AccessDecision.ALLOW) allowed.add(grant.permission());
        if (!allowed.contains(ClaimPermission.INTERACT)) return null;
        return allowed.contains(ClaimPermission.BUILD) || allowed.contains(ClaimPermission.CONTAINERS) ? MEMBER : VISITOR;
    }
}
