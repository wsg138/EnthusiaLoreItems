package net.enthusia.loreitems.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class DoubleChestPaperFixtureTest {
    @Test
    void positiveAndNegativeCrossChunkCoordinatesUseFloorDivision() {
        assertChunks("same", 0, 0, 0, 0);
        assertChunks("xplus", 0, 0, 1, 0);
        assertChunks("zplus", 0, 0, 0, 1);
        assertChunks("xminus", -1, 0, 0, 0);
        assertChunks("zminus", 0, -1, 0, 0);
    }

    @Test
    void pairNeverClaimsFullAccessWhenOnlyOneOfItsChunksIsLoaded() {
        for (String name : new String[]{"xplus", "zplus", "xminus", "zminus"}) {
            var pair = DoubleChestPaperFixture.caseFor(name);
            Set<String> checked = new HashSet<>();
            World onlyFirst = world((x, z) -> {
                checked.add(x + ":" + z);
                return x == pair.chunkX1() && z == pair.chunkZ1();
            });
            assertFalse(pair.loaded(onlyFirst), name);
            assertEquals(2, checked.size(), name);

            checked.clear();
            World onlySecond = world((x, z) -> {
                checked.add(x + ":" + z);
                return x == pair.chunkX2() && z == pair.chunkZ2();
            });
            assertFalse(pair.loaded(onlySecond), name);
            assertEquals(1, checked.size(), "short-circuit is allowed only after absent first half");
        }
    }

    @Test
    void bothLoadedOnlyRequiresChunkStatusNeverBlockReads() {
        var pair = DoubleChestPaperFixture.caseFor("xplus");
        World world = world((x, z) -> true);
        assertTrue(pair.loaded(world));
    }

    @Test
    void rejectsUnexpectedScenarioNamesWithoutReadingWorldState() {
        assertThrows(IllegalArgumentException.class,
                () -> DoubleChestPaperFixture.caseFor("../production"));
        assertThrows(IllegalArgumentException.class,
                () -> DoubleChestPaperFixture.caseFor("reload"));
    }

    private static void assertChunks(
            String name, int firstX, int firstZ, int secondX, int secondZ) {
        var pair = DoubleChestPaperFixture.caseFor(name);
        assertEquals(firstX, pair.chunkX1());
        assertEquals(firstZ, pair.chunkZ1());
        assertEquals(secondX, pair.chunkX2());
        assertEquals(secondZ, pair.chunkZ2());
    }

    private interface Loaded {
        boolean contains(int x, int z);
    }

    private static World world(Loaded loaded) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if ("isChunkLoaded".equals(method.getName())) {
                        return loaded.contains((int) args[0], (int) args[1]);
                    }
                    throw new AssertionError(
                            "world fixture accessed unapproved API " + method.getName());
                });
    }
}
