package dev.toka.land.model;

import java.util.Objects;

/** Default access behavior is separate from owner identity and explicit ACL grants. */
public record AccessPolicy(Mode mode, boolean locked) {
    public enum Mode { PRIVATE, OPEN, PROTECT }
    public AccessPolicy { Objects.requireNonNull(mode, "mode"); }
    public static AccessPolicy privateDefault() { return new AccessPolicy(Mode.PRIVATE, false); }
}
