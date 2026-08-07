package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperGroupFileCatalogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesOnlyRequiredGroupDirectoriesAndParsesIdentityForms() throws Exception {
        PaperGroupFileCatalog catalog = new PaperGroupFileCatalog(temporaryDirectory);
        catalog.initializeDirectories();
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("groups")));
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("groups/completed")));
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("groups/cancelled")));

        UUID uuid = UUID.randomUUID();
        write("launch.yml", """
                display-name: Launch group
                players:
                  - JavaPlayer
                  - '*BedRockPlayer'
                  - %s
                """.formatted(uuid));
        GroupFileCatalogSnapshot snapshot = catalog.reload();
        assertEquals(1, snapshot.validFiles().size());
        assertTrue(snapshot.invalidFiles().isEmpty());
        GroupFileDefinition file = snapshot.validFiles().getFirst();
        assertEquals("Launch group", file.displayName());
        assertEquals(3, file.recipients().size());
        assertEquals("JavaPlayer", file.recipients().get(0).originalValue());
        assertEquals("*BedRockPlayer", file.recipients().get(1).originalValue());
        assertEquals(uuid, file.recipients().get(2).explicitPlayerId());
    }

    @Test
    void rejectsCaseOnlyAndUuidDuplicatesBeforeStart() throws Exception {
        UUID uuid = UUID.randomUUID();
        write("dupes.yml", """
                display-name: Duplicates
                players:
                  - SomePlayer
                  - someplayer
                  - %s
                  - %s
                """.formatted(uuid.toString().toUpperCase(), uuid));
        GroupFileCatalogSnapshot snapshot = new PaperGroupFileCatalog(temporaryDirectory).reload();
        assertTrue(snapshot.validFiles().isEmpty());
        assertEquals(1, snapshot.invalidFiles().size());
        assertTrue(snapshot.invalidFiles().getFirst().diagnostics().getFirst().contains("duplicates"));
    }

    @Test
    void rejectsUnknownKeysMalformedUuidAndNonStringEntries() throws Exception {
        write("unknown.yml", """
                display-name: Unknown
                extra: nope
                players:
                  - Player
                """);
        write("uuid.yml", """
                display-name: UUID
                players:
                  - 01234567-89ab-cdef-0123-456789abcdeg
                """);
        write("typed.yml", """
                display-name: Typed
                players:
                  - 42
                """);
        GroupFileCatalogSnapshot snapshot = new PaperGroupFileCatalog(temporaryDirectory).reload();
        assertTrue(snapshot.validFiles().isEmpty());
        assertEquals(3, snapshot.invalidFiles().size());
    }

    @Test
    void rejectsMalformedYamlTraversalAndSymlinks() throws Exception {
        write("bad.yml", "display-name: [\nplayers: nope\n");
        PaperGroupFileCatalog catalog = new PaperGroupFileCatalog(temporaryDirectory);
        assertThrows(IllegalArgumentException.class, () -> catalog.inspect("../bad.yml"));
        GroupFileCatalogSnapshot malformed = catalog.reload();
        assertEquals(1, malformed.invalidFiles().size());

        Path target = temporaryDirectory.resolve("outside.yml");
        Files.writeString(target, "display-name: Outside\nplayers:\n  - Player\n");
        Path link = temporaryDirectory.resolve("groups/link.yml");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable in this test environment");
            return;
        }
        GroupFileCatalogSnapshot withLink = catalog.reload();
        assertTrue(withLink.invalidFiles().stream()
                .anyMatch(failure -> failure.sourceName().equals("link.yml")));
    }

    @Test
    void fingerprintIsDeterministicAndActiveMarkersAreNotRediscovered() throws Exception {
        String content = "display-name: Stable\nplayers:\n  - Player\n";
        write("stable.yml", content);
        PaperGroupFileCatalog catalog = new PaperGroupFileCatalog(temporaryDirectory);
        GroupFileDefinition first = catalog.inspect("stable.yml");
        GroupFileDefinition second = catalog.inspect("stable.yml");
        assertEquals(first.sourceFingerprint(), second.sourceFingerprint());

        UUID campaignId = UUID.randomUUID();
        Path active = catalog.moveToActive(first, campaignId);
        assertTrue(Files.isRegularFile(active));
        GroupFileCatalogSnapshot snapshot = catalog.reload();
        assertFalse(snapshot.validFiles().stream()
                .anyMatch(file -> file.sourceName().contains(campaignId.toString())));
    }

    @Test
    void fingerprintChangesWithPathOrContent() throws Exception {
        String content = "display-name: Stable\nplayers:\n  - Player\n";
        write("first.yml", content);
        write("second.yml", content);
        PaperGroupFileCatalog catalog = new PaperGroupFileCatalog(temporaryDirectory);
        String first = catalog.inspect("first.yml").sourceFingerprint();
        String second = catalog.inspect("second.yml").sourceFingerprint();
        assertFalse(first.equals(second));
        write("first.yml", content + "# changed\n");
        String changed = catalog.inspect("first.yml").sourceFingerprint();
        assertFalse(first.equals(changed));
    }

    private void write(String name, String content) throws IOException {
        Path groups = temporaryDirectory.resolve("groups");
        Files.createDirectories(groups);
        Files.write(groups.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }
}
