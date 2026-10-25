package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import dev.toka.land.model.LandSelection;
import java.lang.reflect.Method;

/**
 * Draws the box of a selection or a claim through BoundingBoxAPI when that plugin is there; without it nothing is drawn and nothing fails.
 * The plugin is reached by reflection, so tokaLand does not depend on it.
 */
public final class BoundingBoxLink {
    private BoundingBoxLink() { }

    private static Plugin api(Plugin from) {
        Plugin plugin = from.getServer().getPluginManager().getPlugin("BoundingBoxAPI");
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    /** Shows the box of one selection; a selection that ignores height is drawn over the whole height of the world. */
    public static void show(Plugin from, Player player, LandSelection part, boolean wholeHeight, String tag) {
        Plugin api = api(from);
        if (api == null || player.getLevel() == null || !player.getLevel().getFolderName().equals(part.world())) return;
        var dimension = player.getLevel().getDimensionData();
        int low = wholeHeight ? dimension.getMinHeight() : part.minY();
        int high = wholeHeight ? dimension.getMaxHeight() : part.maxY();
        try {
            Method show = api.getClass().getMethod("showBox", Player.class, cn.nukkit.level.Level.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, String.class, int.class);
            show.invoke(api, player, player.getLevel(), part.minX(), low, part.minZ(), part.maxX(), high, part.maxZ(), tag, 0);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            from.getLogger().debug("BoundingBoxAPI cannot be used: " + unavailable);
        }
    }

    public static void clear(Plugin from, Player player, String tag) {
        Plugin api = api(from);
        if (api == null) return;
        try {
            api.getClass().getMethod("clearBox", Player.class, String.class).invoke(api, player, tag);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            from.getLogger().debug("BoundingBoxAPI cannot be used: " + unavailable);
        }
    }
}
