package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EncodedItemTemplateTest {
    @Test
    void payloadIsDefensivelyCopied() {
        byte[] source = {1, 2, 3};
        EncodedItemTemplate template = new EncodedItemTemplate(1, source);

        source[0] = 9;
        byte[] returned = template.payload();
        returned[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, template.payload());
        assertEquals(new EncodedItemTemplate(1, new byte[] {1, 2, 3}), template);
        assertNotEquals(new EncodedItemTemplate(2, new byte[] {1, 2, 3}), template);
    }

    @Test
    void rejectsInvalidVersionAndPayloadSize() {
        assertThrows(IllegalArgumentException.class, () -> new EncodedItemTemplate(0, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new EncodedItemTemplate(1, new byte[0]));
    }
}
