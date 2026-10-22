package dev.toka.land.model;

import java.util.*;
import java.util.regex.Pattern;

/** Facility types from configuration: how far a selection reaches below and above the clicked blocks. */
public final class FacilityTypes {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,15}");
    private static final int MAX_REACH = 256;
    /** Subcommands of /land fac; a type with one of these names could never be created. */
    public static final Set<String> SUBCOMMANDS = Set.of("list", "del", "type", "mode", "lock", "help");

    public record Reach(int below, int above) {
        public Reach {
            if (below < 0 || above < 0 || below > MAX_REACH || above > MAX_REACH) throw new IllegalArgumentException("INVALID_REACH");
        }
    }

    private final Map<String, Reach> types;

    public FacilityTypes(Map<String, Reach> types) {
        Map<String, Reach> checked = new TreeMap<>();
        types.forEach((name, reach) -> {
            String key = name.toLowerCase(Locale.ROOT);
            if (!NAME.matcher(key).matches() || SUBCOMMANDS.contains(key)) throw new IllegalArgumentException("INVALID_FACILITY_TYPE: " + name);
            checked.put(key, Objects.requireNonNull(reach));
        });
        this.types = Map.copyOf(checked);
    }

    public Set<String> names() { return types.keySet(); }

    public boolean has(String type) { return type != null && types.containsKey(type.toLowerCase(Locale.ROOT)); }

    /** Spans both corners, then extends the whole box so a slope is never narrower than its endpoints. */
    public LandSelection select(String type, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        Reach reach = types.get(type == null ? "" : type.toLowerCase(Locale.ROOT));
        if (reach == null) throw new IllegalArgumentException("UNKNOWN_FACILITY_TYPE");
        return new LandSelection(world, x1, (int) Math.max(Integer.MIN_VALUE, (long) Math.min(y1, y2) - reach.below()), z1,
                x2, (int) Math.min(Integer.MAX_VALUE, (long) Math.max(y1, y2) + reach.above()), z2, false);
    }
}
