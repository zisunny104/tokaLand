package dev.toka.land.model;

import java.util.*;

/** Native Land data; legacy extraction and identity resolution are performed by the caller. */
/**
 * @param refundValue what deleting the claim pays back, fixed when it was bought (null: a claim from before this existed)
 * @param refundSource the account that paid for it and pays the refund; null when nothing holds the money
 * @param teleportAccess who may use the teleport point, a {@link TeleportAccess} name; null: what the claim always had (see {@link TeleportAccess#of})
 */
public record ClaimDetails(UUID creatorId, String creatorName, TeleportPoint teleport, boolean publicTeleport,
                           Long refundValue, UUID refundSource, String teleportAccess) {
    public ClaimDetails(UUID creatorId, String creatorName, TeleportPoint teleport, boolean publicTeleport) {
        this(creatorId, creatorName, teleport, publicTeleport, null, null, null);
    }
    public ClaimDetails(UUID creatorId, String creatorName, TeleportPoint teleport, boolean publicTeleport, Long refundValue, UUID refundSource) {
        this(creatorId, creatorName, teleport, publicTeleport, refundValue, refundSource, null);
    }
    public ClaimDetails {
        if (refundValue != null && refundValue < 0) throw new IllegalArgumentException("refundValue must not be negative");
    }
    public ClaimDetails withRefundValue(long value) {
        return new ClaimDetails(creatorId, creatorName, teleport, publicTeleport, value, refundSource, teleportAccess);
    }
    public ClaimDetails withTeleport(TeleportPoint point) {
        return new ClaimDetails(creatorId, creatorName, point, publicTeleport, refundValue, refundSource, teleportAccess);
    }
    public ClaimDetails withTeleportAccess(TeleportAccess access) {
        return new ClaimDetails(creatorId, creatorName, teleport, publicTeleport, refundValue, refundSource, access.name());
    }

    public record TeleportPoint(String world, double x, double y, double z, float yaw, float pitch) {
        public TeleportPoint {
            if(world==null||world.isBlank()||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                    ||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("invalid teleport point");
            if(!blockCoordinate(x)||!blockCoordinate(y)||!blockCoordinate(z))
                throw new IllegalArgumentException("teleport point is outside integer block coordinates");
        }
        private static boolean blockCoordinate(double value){return value>=Integer.MIN_VALUE&&value<(long)Integer.MAX_VALUE+1;}
    }
    public static ClaimDetails privateDefaults(){return new ClaimDetails(null,null,null,false);}
}
