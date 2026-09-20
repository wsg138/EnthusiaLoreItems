package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StartupActivationSequenceTest {
    @Test
    void firstFailedActivationPreventsLaterComponentsFromStarting() {
        List<String> attempted = new ArrayList<>();

        boolean complete = StartupActivationSequence.run(
                () -> record(attempted, "tracking", true),
                () -> record(attempted, "delivery", false),
                () -> record(attempted, "mutation", true),
                () -> record(attempted, "administration", true),
                () -> record(attempted, "distribution", true));

        assertFalse(complete);
        assertEquals(List.of("tracking", "delivery"), attempted);
    }

    private static boolean record(List<String> attempted, String name, boolean result) {
        attempted.add(name);
        return result;
    }
}
