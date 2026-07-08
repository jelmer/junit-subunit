package io.github.jelmer.junitsubunit.sample;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class MixedSample {

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
