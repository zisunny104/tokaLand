package dev.toka.land.model;

import java.util.Objects;
import java.util.UUID;

/** One cuboid component of a claim; several components can form an irregular claim shape. */
public record LandSelection(UUID id, String world, int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ, boolean ignoreHeight) {
    /** New selections receive their ID once; persisted data must retain it when read or edited. */
    public LandSelection(String world, int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ, boolean ignoreHeight) {
        this(UUID.randomUUID(), world, minX, minY, minZ, maxX, maxY, maxZ, ignoreHeight);
    }
    public LandSelection {
        Objects.requireNonNull(id, "selection id is required");
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
        int x1 = Math.min(minX, maxX), x2 = Math.max(minX, maxX);
        int y1 = Math.min(minY, maxY), y2 = Math.max(minY, maxY);
        int z1 = Math.min(minZ, maxZ), z2 = Math.max(minZ, maxZ);
        minX = x1; maxX = x2; minY = y1; maxY = y2; minZ = z1; maxZ = z2;
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && z >= minZ && z <= maxZ
                && (ignoreHeight || y >= minY && y <= maxY);
    }

    public boolean contains(LandSelection other) {
        Objects.requireNonNull(other, "other");
        return world.equals(other.world)
                && other.minX >= minX && other.maxX <= maxX
                && other.minZ >= minZ && other.maxZ <= maxZ
                && (ignoreHeight || (!other.ignoreHeight && other.minY >= minY && other.maxY <= maxY));
    }
}
