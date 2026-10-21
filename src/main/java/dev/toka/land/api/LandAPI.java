package dev.toka.land.api;

import dev.toka.land.Main;
import dev.toka.land.model.LandClaim;
import java.util.*;

/** Read-only cross-plugin API for the initial extraction stage. */
public final class LandAPI {
    private LandAPI() {}
    public static boolean isReady() { return Main.instance() != null && Main.instance().isReady(); }
    public static List<LandClaim> findAllAt(String world, int x, int y, int z) {
        Main main = requireMain();
        return main.findAllAt(world, x, y, z);
    }
    public static Optional<LandClaim> get(UUID id) { return requireMain().getClaim(id); }
    private static Main requireMain() {
        Main main = Main.instance();
        if (main == null || !main.isReady()) throw new IllegalStateException("tokaLand is not ready");
        return main;
    }
}
