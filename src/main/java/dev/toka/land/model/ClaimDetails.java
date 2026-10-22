package dev.toka.land.model;

import java.util.*;

/** Native Land data; legacy extraction and identity resolution are performed by the caller. */
public record ClaimDetails(UUID creatorId, String creatorName, TeleportPoint teleport, boolean publicTeleport) {
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
