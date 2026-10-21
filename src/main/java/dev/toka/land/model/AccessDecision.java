package dev.toka.land.model;

/** A local child claim can explicitly grant or deny a permission; absence means inherit. */
public enum AccessDecision {
    ALLOW,
    DENY
}
