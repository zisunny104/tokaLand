package dev.toka.land.lease;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class LeaseInputTest {
    @Test public void blankDateMeansNotGiven() {
        assertNull(LeaseInput.parseDate(""));
        assertNull(LeaseInput.parseDate("  "));
        assertNull(LeaseInput.parseDate(null));
    }

    @Test public void dateMustBeARealIsoDay() {
        assertEquals(LocalDate.of(2026, 12, 31), LeaseInput.parseDate(" 2026-12-31 "));
        for (String bad : new String[] {"2026-02-30", "2026/12/31", "26-12-31", "tomorrow", "2026-13-01"}) {
            try { LeaseInput.parseDate(bad); fail(bad); } catch (DateTimeParseException expected) { /* rejected */ }
        }
    }

    @Test public void endDateIsStartPlusPaidDaysPlusOneDayOfSlack() {
        assertEquals(LocalDate.of(2026, 4, 2), LeaseInput.endDate(LocalDate.of(2026, 1, 1), 1, 90));
        assertEquals(LocalDate.of(2026, 1, 1).plusDays(2 * 30 + 1), LeaseInput.endDate(LocalDate.of(2026, 1, 1), 2, 30));
    }

    @Test public void searchNeedsEveryWordInSomeFieldIgnoringCase() {
        assertTrue(LeaseInput.matches("", "Shop"));
        assertTrue(LeaseInput.matches(null, "Shop"));
        assertTrue(LeaseInput.matches("sho", "Corner Shop", "abc"));
        assertTrue(LeaseInput.matches("corner abc", "Corner Shop", "abc"));
        assertFalse(LeaseInput.matches("corner xyz", "Corner Shop", "abc"));
        assertFalse(LeaseInput.matches("x", (String) null));
    }

    @Test public void moneyHasThousandsSeparators() {
        assertEquals("1,234,567", LeaseInput.money(1_234_567));
        assertEquals("12", LeaseInput.money(12));
    }

    @Test public void dayChoicesAddNoTermOnlyWhenAllowed() {
        assertEquals(List.of(30, 90, 180, 365), LeaseInput.dayChoices(false));
        assertEquals(List.of(30, 90, 180, 365, -1), LeaseInput.dayChoices(true));
    }

    @Test public void commandSafeNamesHaveNoWhitespaceAndAreShort() {
        assertTrue(LeaseInput.commandSafe("小屋"));
        assertFalse(LeaseInput.commandSafe("a b"));
        assertFalse(LeaseInput.commandSafe(" "));
        assertFalse(LeaseInput.commandSafe(null));
        assertFalse(LeaseInput.commandSafe("x".repeat(33)));
    }

    @Test public void termsAreClampedWhenApplied() {
        Lease lease = new Lease();
        new LeaseTerms(500, 500, false, -5, 500).applyTo(lease);
        assertEquals(LeaseTerms.MAX_PERIODS, lease.maxPeriods);
        assertEquals(LeaseTerms.MAX_GRACE, lease.graceDays);
        assertFalse(lease.autoRenew);
        assertEquals(0, lease.discountAfter);
        assertEquals(LeaseTerms.MAX_DISCOUNT, lease.discountPercent);
        new LeaseTerms(null, null, null, null, null).applyTo(lease);
        assertEquals(LeaseTerms.MAX_PERIODS, lease.maxPeriods);
        new LeaseTerms(0, -1, null, null, null).applyTo(lease);
        assertEquals(1, lease.maxPeriods);
        assertEquals(0, lease.graceDays);
    }

    private static Set<String> keys(String resource) throws IOException {
        Path file = Path.of("src/main/resources/lang", resource);
        Set<String> found = new LinkedHashSet<>();
        List<String> path = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            int colon = line.indexOf(':', indent);
            String name = line.substring(indent, colon);
            while (path.size() > indent / 2) path.remove(path.size() - 1);
            path.add(name);
            if (!line.substring(colon + 1).isBlank()) found.add(String.join(".", path));
        }
        return found;
    }

    @Test public void bothLanguagesHaveTheSameKeys() throws IOException {
        Set<String> zh = keys("zh-TW.yml"), en = keys("en-US.yml");
        Set<String> onlyZh = new LinkedHashSet<>(zh), onlyEn = new LinkedHashSet<>(en);
        onlyZh.removeAll(en);
        onlyEn.removeAll(zh);
        assertTrue("only zh-TW: " + onlyZh, onlyZh.isEmpty());
        assertTrue("only en-US: " + onlyEn, onlyEn.isEmpty());
    }
}
