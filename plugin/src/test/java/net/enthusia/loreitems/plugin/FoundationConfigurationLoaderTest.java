package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.enthusia.loreitems.application.FoundationConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationConfigurationLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndLoadsPackagedDefaults() throws Exception {
        FoundationConfiguration loaded =
                new FoundationConfigurationLoader(temporaryDirectory).loadOrCreate();

        assertEquals(FoundationConfiguration.defaults(), loaded);
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("config.yml")));
    }

    @Test
    void rejectsUnknownKeysInsteadOfIgnoringTypos() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("config.yml"),
                "database-queue-capcity: 500\n",
                StandardCharsets.UTF_8);

        assertThrows(
                IllegalArgumentException.class,
                () -> new FoundationConfigurationLoader(temporaryDirectory).loadOrCreate());
    }
}
