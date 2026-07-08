package io.github.jelmer.junitsubunit.sample;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

// Fixture data for MainIntegrationTest, which drives it via the JUnit Platform
// Launcher with explicit selectors. Named *Fixture (not *Test) so surefire and
// other convention-based runners skip it.
public class MixedFixture {

    @Test
    void ok() {
        assertEquals(1, 1);
    }

    @Test
    void boom() {
        fail("boom!");
    }

    @Test
    void skippedByAssumption() {
        Assumptions.assumeTrue(false, "not today");
        fail("should never reach here");
    }
}
