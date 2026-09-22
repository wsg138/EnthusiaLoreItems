package net.enthusia.loreitems.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Guards the complete operator-visible command and permission contract in plugin.yml. */
final class PluginFeatureSurfaceContractTest {
    private static final Set<String> EXPECTED_COMMANDS = Set.of(
            "loreitems",
            "loreitemsreview",
            "loredistribution"
    );

    private static final Set<String> EXPECTED_PERMISSIONS = Set.of(
            "enthusia.loreitems.admin.create",
            "enthusia.loreitems.admin.adopt",
            "enthusia.loreitems.admin.give",
            "enthusia.loreitems.admin.reload",
            "enthusia.loreitems.admin.audit",
            "enthusia.loreitems.admin.recovery.review",
            "enthusia.loreitems.admin.edit",
            "enthusia.loreitems.admin.remove",
            "enthusia.loreitems.admin.purge",
            "enthusia.loreitems.admin.delete",
            "enthusia.loreitems.admin.destructive.inspect",
            "enthusia.loreitems.admin.destructive.control",
            "enthusia.loreitems.admin.destructive.review",
            "enthusia.loreitems.admin.distribution.inspect",
            "enthusia.loreitems.admin.distribution.start",
            "enthusia.loreitems.admin.distribution.control"
    );

    private static final Map<String, List<String>> REQUIRED_USAGE_TERMS = Map.of(
            "loreitems", List.of(
                    "create", "adopt", "give", "reload", "browse", "anomalies", "audit", "recovery",
                    "remove", "purge", "delete", "operations", "targets", "destructive-metrics",
                    "pause-operation", "resume-operation", "resolve-removal"
            ),
            "loreitemsreview", List.of("retry", "cancel", "evidence"),
            "loredistribution", List.of(
                    "reload", "inspect", "preview", "confirm", "campaigns", "status", "recipients",
                    "pause", "resume", "cancel", "reconcile"
            )
    );

    @Test
    void pluginManifestContainsExactlyTheReviewedCommandAndPermissionSurface() throws Exception {
        Manifest manifest = manifest();
        assertEquals(new TreeSet<>(EXPECTED_COMMANDS), new TreeSet<>(manifest.commands().keySet()));
        assertEquals(new TreeSet<>(EXPECTED_PERMISSIONS), new TreeSet<>(manifest.permissions().keySet()));
    }

    @Test
    void everyCommandHasOperatorDocumentationForItsFeatureRoutes() throws Exception {
        Manifest manifest = manifest();
        REQUIRED_USAGE_TERMS.forEach((command, requiredTerms) -> {
            String usage = manifest.commands().get(command).getOrDefault("usage", "");
            String description = manifest.commands().get(command).getOrDefault("description", "");
            assertTrue(!description.isBlank(), command + " must retain a description");
            assertTrue(!usage.isBlank(), command + " must retain usage documentation");
            requiredTerms.forEach(term -> assertTrue(
                    usage.contains(term),
                    () -> command + " usage lost reviewed route '" + term + "': " + usage
            ));
        });
    }

    @Test
    void privilegedPermissionDefaultsRemainOperatorOnly() throws Exception {
        Manifest manifest = manifest();
        manifest.permissions().forEach((permission, fields) -> assertEquals(
                "op",
                fields.get("default"),
                permission + " must remain fail-closed for ordinary players"
        ));
    }

    @Test
    void outerCommandPermissionBoundaryRemainsIntentional() throws Exception {
        Manifest manifest = manifest();
        Set<String> withoutOuterPermission = new TreeSet<>();
        manifest.commands().forEach((command, fields) -> {
            String permission = fields.get("permission");
            if (permission == null || permission.isBlank()) {
                withoutOuterPermission.add(command);
            } else {
                assertTrue(manifest.permissions().containsKey(permission),
                        command + " references undeclared permission " + permission);
            }
        });
        assertEquals(Set.of("loreitems", "loredistribution"), withoutOuterPermission);
    }

    private static Manifest manifest() throws Exception {
        Path plugin = repositoryRoot().resolve("plugin/src/main/resources/plugin.yml");
        List<String> lines = Files.readAllLines(plugin);
        return new Manifest(parseSection(lines, "commands"), parseSection(lines, "permissions"));
    }

    private static Map<String, Map<String, String>> parseSection(List<String> lines, String section) {
        Map<String, Map<String, String>> entries = new LinkedHashMap<>();
        boolean inside = false;
        String current = null;

        for (String line : lines) {
            if (line.equals(section + ":")) {
                inside = true;
                current = null;
                continue;
            }
            if (!inside) {
                continue;
            }
            if (!line.isBlank() && !line.startsWith(" ")) {
                break;
            }
            if (line.startsWith("  ") && !line.startsWith("    ") && line.trim().endsWith(":")) {
                current = line.trim().substring(0, line.trim().length() - 1);
                entries.put(current, new LinkedHashMap<>());
                continue;
            }
            if (current != null && line.startsWith("    ")) {
                String trimmed = line.trim();
                int split = trimmed.indexOf(':');
                if (split > 0) {
                    String key = trimmed.substring(0, split).trim();
                    String value = trimmed.substring(split + 1).trim();
                    entries.get(current).put(key, unquote(value));
                }
            }
        }
        return entries;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("settings.gradle.kts"))) {
            return parent;
        }
        throw new IllegalStateException("Could not locate LoreItems repository root from " + current);
    }

    private record Manifest(
            Map<String, Map<String, String>> commands,
            Map<String, Map<String, String>> permissions
    ) {
    }
}
