package dev.toka.land.model;

import java.util.Locale;
import java.util.Set;

/** Names that would collide with subcommands; trailing underscores (legacy name joining) do not hide them. */
public final class ReservedNames {
    private static final Set<String> WORDS = Set.of("help", "list", "set", "del", "edit");
    private ReservedNames() {}

    public static boolean is(String name) {
        return name == null || name.isBlank() || WORDS.contains(name.strip().replaceAll("_+$", "").toLowerCase(Locale.ROOT));
    }
}
