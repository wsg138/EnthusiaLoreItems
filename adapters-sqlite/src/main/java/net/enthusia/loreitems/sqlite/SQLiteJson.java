package net.enthusia.loreitems.sqlite;

final class SQLiteJson {
    private static final int EXTRA_ESCAPE_CAPACITY = 16;
    private static final int CONTROL_CHARACTER_LIMIT = 0x20;

    private SQLiteJson() {
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + EXTRA_ESCAPE_CAPACITY);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> appendOrdinaryOrControl(escaped, character);
            }
        }
        return escaped.toString();
    }

    private static void appendOrdinaryOrControl(StringBuilder escaped, char character) {
        if (character < CONTROL_CHARACTER_LIMIT) {
            escaped.append(String.format("\\u%04x", (int) character));
        } else {
            escaped.append(character);
        }
    }
}
