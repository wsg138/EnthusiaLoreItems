package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Startup discovery is metadata-only and consumes no more than its tick budget. */
class PaperStartupChunkDiscoveryTest {
    private ServerMock server;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void startupKeysAreEmittedOnceAndPacedByBudget() {
        World world = server.addSimpleWorld("startup");
        world.getChunkAt(8, 8);
        world.getChunkAt(9, 8);
        List<PaperTrackingScanRequest.ChunkReference> expected = new ArrayList<>();
        for (World presentWorld : server.getWorlds()) {
            for (Chunk chunk : presentWorld.getLoadedChunks()) {
                expected.add(PaperTrackingScanRequest.ChunkReference.capture(chunk));
            }
        }
        assertTrue(expected.size() >= 2);

        PaperStartupChunkDiscovery discovery = new PaperStartupChunkDiscovery(plugin);
        discovery.capture();
        List<PaperTrackingScanRequest.ChunkReference> observed = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        discovery.seed(0, (key, source) -> {
            observed.add(key);
            sources.add(source);
        });
        assertTrue(observed.isEmpty());

        for (int count = 0; count < expected.size() + 2; count++) {
            int before = observed.size();
            discovery.seed(1, (key, source) -> {
                observed.add(key);
                sources.add(source);
            });
            assertTrue(observed.size() - before <= 1);
        }
        assertEquals(expected, observed);
        assertTrue(sources.stream().allMatch("startup-loaded-chunk"::equals));
        discovery.clear();
        discovery.seed(5, (key, source) -> observed.add(key));
        assertEquals(expected, observed);
    }
}
