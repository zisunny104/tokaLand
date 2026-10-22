package dev.toka.land.model;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** A typed principal in the shared claim ACL. Unresolved names never authorize by themselves. */
public record AccessSubject(Type type, String id, String provider) {
    public enum Type { PLAYER_UUID, GROUP, LEGACY_NAME }

    public AccessSubject {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("subject id is required");
        id = id.strip();
        provider = provider == null ? "" : provider.strip();
        switch (type) {
            case PLAYER_UUID -> {
                UUID parsed = UUID.fromString(id);
                if (!parsed.toString().equalsIgnoreCase(id) || !provider.isEmpty())
                    throw new IllegalArgumentException("invalid player UUID subject");
                id = parsed.toString();
            }
            case GROUP -> {
                if (provider.isEmpty()) throw new IllegalArgumentException("group provider is required");
                provider = provider.toLowerCase(Locale.ROOT);
            }
            case LEGACY_NAME -> {
                if (!provider.isEmpty()) throw new IllegalArgumentException("legacy name cannot have a provider");
            }
        }
    }

    public static AccessSubject player(UUID id) {
        return new AccessSubject(Type.PLAYER_UUID, Objects.requireNonNull(id).toString(), "");
    }
    public static AccessSubject group(String provider, String groupId) {
        return new AccessSubject(Type.GROUP, groupId, provider);
    }
    public static AccessSubject legacyName(String name) {
        return new AccessSubject(Type.LEGACY_NAME, name, "");
    }
}
