package dev.toka.land.model;

/** Fine-grained claim permissions. Server-wide capabilities remain the permission plugin's concern. */
public enum ClaimPermission {
    INTERACT,
    BUILD,
    CONTAINERS,
    MANAGE,
    CREATE_CHILD
}
