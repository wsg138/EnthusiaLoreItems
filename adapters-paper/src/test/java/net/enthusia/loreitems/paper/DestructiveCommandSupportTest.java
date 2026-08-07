package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DestructiveCommandSupportTest {
    @Test
    void rejectsPageOffsetOverflowAsArgumentError() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DestructiveCommandSupport.pageRequest(
                        new String[]{"operations", Integer.toString(Integer.MAX_VALUE)},
                        1,
                        50));

        assertEquals("That page number is too large.", exception.getMessage());
    }
}
