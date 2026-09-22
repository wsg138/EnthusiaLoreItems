package net.enthusia.loreitems.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Durable coverage inventory for the production requirements.
 *
 * <p>Every user-visible or recovery-critical LoreItems capability must stay attached to at least
 * one concrete automated unit/integration/acceptance gate. Removing or renaming the evidence is
 * therefore a test failure instead of silently reducing coverage.</p>
 */
final class FullFeatureCoverageContractTest {
    private static final Map<String, List<String>> REQUIRED_COVERAGE = coverage();

    @Test
    void everyProductionFeatureHasDurableAutomatedCoverage() {
        Path root = repositoryRoot();
        REQUIRED_COVERAGE.forEach((feature, relativePaths) -> relativePaths.forEach(relativePath ->
                assertTrue(
                        Files.isRegularFile(root.resolve(relativePath)),
                        () -> feature + " lost required automated coverage: " + relativePath
                )
        ));
    }

    @Test
    void acceptanceWorkflowsRemainExecutableAndFailClosed() throws Exception {
        Path root = repositoryRoot();
        for (List<String> paths : REQUIRED_COVERAGE.values()) {
            for (String relativePath : paths) {
                if (!relativePath.startsWith(".github/workflows/")) {
                    continue;
                }
                String workflow = Files.readString(root.resolve(relativePath));
                assertTrue(workflow.contains("workflow_dispatch"),
                        relativePath + " must remain explicitly executable for acceptance");
                assertTrue(workflow.contains("set -e") || workflow.contains("set -euo pipefail"),
                        relativePath + " must fail closed on shell errors");
            }
        }
    }

    private static Map<String, List<String>> coverage() {
        Map<String, List<String>> coverage = new LinkedHashMap<>();

        coverage.put("hidden definition/instance identity and persistence model", List.of(
                "domain/src/test/java/net/enthusia/loreitems/domain/LorePersistenceModelTest.java",
                ".github/workflows/wp05-tracking-contract-acceptance.yml"
        ));
        coverage.put("deleted-definition tombstones and late returning copies", List.of(
                "domain/src/test/java/net/enthusia/loreitems/domain/DeletedDefinitionMarkerTest.java",
                ".github/workflows/wp05-full-delete-late-copy-acceptance.yml"
        ));
        coverage.put("durable direct give, offline queue, full inventory and idempotency", List.of(
                "domain/src/test/java/net/enthusia/loreitems/domain/DirectDeliveryStateTest.java",
                ".github/workflows/wp05-java-core-acceptance.yml"
        ));
        coverage.put("definition create and held-item adoption", List.of(
                ".github/workflows/wp05-java-core-acceptance.yml"
        ));
        coverage.put("template editor component contract", List.of(
                ".github/workflows/wp05-editor-contract-acceptance.yml"
        ));
        coverage.put("template revision rollout to existing/inaccessible instances", List.of(
                ".github/workflows/wp05-edit-rollout-acceptance.yml"
        ));
        coverage.put("despawn/fire/lava/explosion/cactus/environment protection", List.of(
                ".github/workflows/wp05-protection-acceptance.yml"
        ));
        coverage.put("craft/smelt/grind/smith/rename/consume conversion protection", List.of(
                ".github/workflows/wp05-conversion-protection-acceptance.yml"
        ));
        coverage.put("intentional void loss", List.of(
                ".github/workflows/wp05-protection-acceptance.yml"
        ));
        coverage.put("nested shulker/bundle protection and tracking", List.of(
                ".github/workflows/wp05-tracking-contract-acceptance.yml",
                ".github/workflows/wp05-protection-acceptance.yml"
        ));
        coverage.put("player/container/entity/display tracking categories", List.of(
                ".github/workflows/wp05-tracking-contract-acceptance.yml"
        ));
        coverage.put("duplicate/malformed/conflicting observation anomaly handling", List.of(
                ".github/workflows/wp05-anomaly-contract-acceptance.yml"
        ));
        coverage.put("exact-instance removal", List.of(
                ".github/workflows/wp05-exact-removal-acceptance.yml"
        ));
        coverage.put("purge/delete/pause/resume destructive lifecycle", List.of(
                ".github/workflows/wp05-destructive-lifecycle-acceptance.yml"
        ));
        coverage.put("destructive target evidence review", List.of(
                ".github/workflows/wp05-destructive-lifecycle-acceptance.yml",
                ".github/workflows/wp05-mutation-review-contract-acceptance.yml"
        ));
        coverage.put("one-use distribution campaign state machine", List.of(
                "domain/src/test/java/net/enthusia/loreitems/domain/DistributionCampaignTest.java",
                ".github/workflows/wp05-distribution-acceptance.yml"
        ));
        coverage.put("distribution pause/resume/cancel/reconcile and restart", List.of(
                ".github/workflows/wp05-distribution-acceptance.yml",
                ".github/workflows/wp05-mixed-lifecycle-acceptance.yml"
        ));
        coverage.put("Floodgate prefixed/unresolved future recipients", List.of(
                ".github/workflows/wp05-floodgate-distribution-acceptance.yml",
                ".github/workflows/wp05-floodgate-fix-regression.yml"
        ));
        coverage.put("queued mutation state transitions", List.of(
                "domain/src/test/java/net/enthusia/loreitems/domain/PendingMutationStateTest.java",
                ".github/workflows/wp05-mixed-lifecycle-acceptance.yml"
        ));
        coverage.put("ambiguous side effects require review instead of guessing", List.of(
                ".github/workflows/wp05-ambiguous-mutation-acceptance.yml",
                ".github/workflows/wp05-mutation-review-contract-acceptance.yml"
        ));
        coverage.put("backup/restart/rollback recovery", List.of(
                ".github/workflows/wp05-backup-rollback-acceptance.yml",
                ".github/workflows/wp05-mixed-lifecycle-acceptance.yml"
        ));
        coverage.put("validated atomic configuration reload", List.of(
                ".github/workflows/wp05-config-reload-acceptance.yml"
        ));
        coverage.put("bounded load/backpressure behavior", List.of(
                ".github/workflows/wp05-load-backpressure-acceptance.yml"
        ));
        coverage.put("environment and operational safety", List.of(
                ".github/workflows/wp05-environment-ops001-acceptance.yml"
        ));
        coverage.put("public idempotent EnthusiaTags service API", List.of(
                ".github/workflows/wp05-api-acceptance.yml"
        ));
        coverage.put("hexagonal dependency boundaries", List.of(
                "architecture-tests/src/test/java/net/enthusia/loreitems/architecture/HexagonalArchitectureTest.java"
        ));

        return Map.copyOf(coverage);
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
}
