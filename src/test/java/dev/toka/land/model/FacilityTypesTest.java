package dev.toka.land.model;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class FacilityTypesTest {
    private final FacilityTypes types = new FacilityTypes(Map.of(
            "road", new FacilityTypes.Reach(2, 5), "rail", new FacilityTypes.Reach(0, 0)));

    @Test public void roadReachesBelowAndAboveTheClickedBlocks() {
        var part = types.select("road", "w", 0, 64, 0, 3, 64, 9);
        assertEquals(62, part.minY());
        assertEquals(69, part.maxY());
        assertFalse(part.ignoreHeight());
    }

    @Test public void slopeIsNeverNarrowerThanItsEndpoints() {
        var part = types.select("road", "w", 0, 64, 0, 3, 70, 9);
        assertEquals(62, part.minY());
        assertEquals(75, part.maxY());
    }

    @Test public void railKeepsTheSelectedBlocksOnly() {
        var part = types.select("RAIL", "w", 0, 10, 0, 1, 10, 1);
        assertEquals(10, part.minY());
        assertEquals(10, part.maxY());
    }

    @Test(expected = IllegalArgumentException.class) public void unknownTypeIsRejected() {
        types.select("bridge", "w", 0, 0, 0, 1, 1, 1);
    }

    @Test(expected = IllegalArgumentException.class) public void invalidTypeNameIsRejected() {
        new FacilityTypes(Map.of("Bad Name", new FacilityTypes.Reach(0, 0)));
    }
}
