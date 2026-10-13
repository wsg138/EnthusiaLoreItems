package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperPhysicalLocationKeysTest {
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void parsesNewWorldUuidAndCanonicalHalfOrderWithoutInterpretingHistory() {
        var left = new PaperDoubleChestIdentity.Position(-1, 70, 0);
        var right = new PaperDoubleChestIdentity.Position(0, 70, 0);
        var pair = new PaperInventoryReference.DoubleChestBlock(WORLD, left, right, right);
        var read = PaperPhysicalLocationKeys.parse(pair.stableKey()).orElseThrow();
        assertEquals(pair, ((PaperPhysicalLocationKeys.UuidPair) read).pair());
        var block = PaperPhysicalLocationKeys.parse("block-v2:" + WORLD + ":-17:70:-16")
                .orElseThrow();
        assertEquals(WORLD, ((PaperPhysicalLocationKeys.UuidBlock) block).worldId());
        var old = PaperPhysicalLocationKeys.parse("minecraft:overworld:-17:70:-16")
                .orElseThrow();
        assertTrue(old instanceof PaperPhysicalLocationKeys.LegacyBlock);
        assertNotEquals(block, old, "Never map a historical world name to a UUID by guessing");
    }

    @Test
    void invalidGeometryBadCoordinatesAndReservedVersionsDoNotResolve() {
        String head = "double-chest:" + WORLD + ":";
        assertTrue(PaperPhysicalLocationKeys.parse(head + "0:70:0|2:70:0:left=0").isEmpty());
        assertTrue(PaperPhysicalLocationKeys.parse(head + "1:70:0|0:70:0:left=1").isEmpty());
        assertTrue(PaperPhysicalLocationKeys.parse(head + "0:70:0|1:70:0:left=3").isEmpty());
        assertTrue(PaperPhysicalLocationKeys.parse("block-v2:not-a-uuid:0:70:0").isEmpty());
        assertTrue(PaperPhysicalLocationKeys.parse("block-v2:" + WORLD
                + ":2147483648:70:0").isEmpty());
        assertTrue(PaperPhysicalLocationKeys.parse("minecraft:overworld:1:2").isEmpty());
        assertTrue(PaperPhysicalLocationKeys.parse("player:" + WORLD).isEmpty());
        assertTrue(PaperPhysicalLocationKeys.parse(null).isEmpty());
    }
}
