package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SQLiteJsonTest {
    @Test
    void escapesJsonSpecialAndControlCharacters() {
        assertEquals("\\\\", SQLiteJson.escape("\\"));
        assertEquals("\\\"", SQLiteJson.escape("\""));
        assertEquals("\\n\\r\\t\\u0001", SQLiteJson.escape("\n\r\t\u0001"));
    }
}
