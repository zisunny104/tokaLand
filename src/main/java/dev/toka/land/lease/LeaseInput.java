package dev.toka.land.lease;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure helpers for what players type into the lease windows. */
public final class LeaseInput {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private LeaseInput() { }

    /** Blank means "not given" (null); anything else has to be a real yyyy-MM-dd date. */
    public static LocalDate parseDate(String text) throws DateTimeParseException {
        if (text == null || text.isBlank()) return null;
        return LocalDate.parse(text.strip(), DATE);
    }

    /** The day a term is shown to end: the start plus the paid days, plus one day of slack, like {@link Lease#shownEnd()}. */
    public static LocalDate endDate(LocalDate start, int periods, int periodDays) {
        return start.plusDays((long) periods * periodDays + 1);
    }

    public static LocalDate today(String zone) { return LocalDate.now(ZoneId.of(zone)); }

    /** Blank query matches everything; otherwise every word has to occur in one of the fields, ignoring case. */
    public static boolean matches(String query, String... fields) {
        if (query == null || query.isBlank()) return true;
        for (String word : query.strip().toLowerCase(Locale.ROOT).split("\s+")) {
            boolean found = false;
            for (String field : fields) if (field != null && field.toLowerCase(Locale.ROOT).contains(word)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    public static String money(long amount) { return String.format(Locale.ROOT, "%,d", amount); }

    /** Day choices of the offer window, plus "no term" when the server allows it (-1). */
    public static List<Integer> dayChoices(boolean perpetual) {
        List<Integer> choices = new ArrayList<>(List.of(30, 90, 180, 365));
        if (perpetual) choices.add(-1);
        return choices;
    }

    /** A claim name that can travel through a command line unchanged. */
    public static boolean commandSafe(String name) {
        return name != null && !name.isBlank() && name.length() <= 32 && name.chars().noneMatch(Character::isWhitespace);
    }
}
