package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginMetadataTest {
    @Test
    void floodgateIsDeclaredAsSoftDependency() throws IOException {
        try (InputStream stream = PluginMetadataTest.class.getResourceAsStream("/plugin.yml")) {
            assertTrue(stream != null, "plugin.yml must be present on the plugin test classpath");
            String pluginYaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(
                    pluginYaml.lines().anyMatch(line -> line.strip().equals("softdepend: [floodgate]")),
                    "plugin.yml must make Floodgate load before LoreItems when Floodgate is installed");
        }
    }
}
