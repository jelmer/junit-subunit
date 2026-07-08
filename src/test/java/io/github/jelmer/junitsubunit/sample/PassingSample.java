package io.github.jelmer.junitsubunit.sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Not named *Test so surefire skips it; MainIntegrationTest drives it via
// the JUnit Platform Launcher.
public class PassingSample {

    @Test
    void addition() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void concatenation() {
        assertEquals("foobar", "foo" + "bar");
    }
}
