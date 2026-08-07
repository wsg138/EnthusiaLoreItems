package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SQLiteDestructiveControlStoreJsonTest {
    @Test
    void escapesEveryJsonControlCharacter() {
        String input = "a" + (char) 0x01 + "b\n\"\\";
        String expected = "a" + "\\u" + "0001" + "b\\n\\\"\\\\";

        assertEquals(expected, SQLiteDestructiveControlStore.escapeJson(input));
    }
}
