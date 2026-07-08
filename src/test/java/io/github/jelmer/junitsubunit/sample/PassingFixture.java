package io.github.jelmer.junitsubunit.sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Fixture data for MainIntegrationTest, which drives it via the JUnit Platform
// Launcher with explicit selectors. Named *Fixture (not *Test) so surefire and
// other convention-based runners skip it.
public class PassingFixture {

    @Test
    void addition() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void concatenation() {
        assertEquals("foobar", "foo" + "bar");
    }
}
