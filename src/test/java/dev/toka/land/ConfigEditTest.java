package dev.toka.land;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigEditTest {
    @Test public void onlyTheNamedLineChangesAndCommentsStay() {
        List<String> lines = new ArrayList<>(List.of("other:", "  active: true", "ownership:", "  # a comment", "  active: false # keep this", "tail: 1"));
        assertTrue(ConfigEdit.apply(lines, "ownership", "active", true));
        assertEquals(List.of("other:", "  active: true", "ownership:", "  # a comment", "  active: true # keep this", "tail: 1"), lines);
    }

    @Test public void aKeyInAnotherSectionIsNotTouched() {
        List<String> lines = new ArrayList<>(List.of("ownership:", "  other: 1", "next:", "  active: false"));
        assertFalse(ConfigEdit.apply(lines, "ownership", "active", true));
        assertEquals("  active: false", lines.get(3));
    }
}
