# WP-04 automated evidence index

This index is the auditable map for the automated production-hardening package. It does not claim live Paper/Leaf acceptance; that remains WP-05.

## Durable state and crash/restart behavior

- Definition creation: `PersistingCreateDefinitionUseCaseTest`, `SQLiteDefinitionRepositoryTest`, and transactional rollback coverage in `SQLiteUnitOfWorkTest`.
- Held-item adoption: `PersistingAdoptHeldItemUseCaseTest` and `SQLiteHeldItemAdoptionStoreTest`.
- Direct/API delivery: `SQLiteDirectDeliveryFailureMatrixTest`, `SQLiteDirectDeliveryRepositoryTest`, `PaperDirectDeliveryOperatorTest`, and `PaperDirectDeliveryWorkerTest`. The matrix covers rollback before intent, intent replay, claimed ambiguity after restart, verification-commit rollback/review escalation, and terminal replay.
- Template revision/update: `PersistingTemplateRevisionRolloutUseCaseTest`, `SQLiteTemplateRevisionRolloutStoreTest`, `SQLiteTemplateRevisionCancellationTest`, `SQLiteTemplateUpdateExecutionStoreTest`, `SQLiteTemplateUpdateSiblingFenceTest`, `PaperTemplateUpdateCoordinatorSaturationTest`, and `PaperTemplateUpdateBacklogTest`.
- Destructive removal/purge/delete/late-copy controls: `SQLiteDestructiveOperationStoreTest`, `SQLiteDeletedDefinitionMarkerRepositoryTest`, `SQLiteDeletedDefinitionMarkerUnitOfWorkTest`, `PaperDestructiveFirstCoordinatorTest`, `PaperDestructiveRemovalOperatorTest`, and `LoreItemsDestructiveCommandExecutorTest`.
- Duplicate/current-state/audit evidence: `SQLiteDuplicateEvidenceWindowTest`, `SQLiteTrackingObservationStoreTest`, `SQLiteTrackingRepositoriesTest`, `SQLiteTrackingAdministrationStoreTest`, and `SQLiteAuditRepositoryTest`.
- Campaign/recipient restart and delivery state: `SQLiteDistributionCampaignRestartTest`, `SQLiteDistributionCampaignStartRepositoryTest`, `SQLiteDistributionCampaignControlRepositoryTest`, `SQLiteDistributionDeliveryRepositoryTest`, `SQLiteCancellableDistributionDeliveryRepositoryTest`, `SQLiteDistributionEndToEndTest`, and `SQLiteDistributionRepositoryTest`.
- Pending/review recovery: `SQLitePendingMutationRepositoryTest`, `SQLitePendingMutationReviewStoreTest`, and `PaperMutationRecoveryWorker` tests in the Paper adapter suite.
- Fixed-seed stateful recovery invariant: `tools/test_wp04_randomized_recovery.py`; seeds `0xE17001` through `0xE17004`, 2,000 transitions per seed. Concrete persistence behavior remains covered by the SQLite/Paper tests above.

## Bounds, saturation, and backpressure

- Database queue: `BoundedDatabaseExecutorTest`.
- Direct delivery: `PaperDirectDeliveryWorkerTest`.
- Template update scan/retry queues: `PaperTemplateUpdateBacklogTest` and `PaperTemplateUpdateCoordinatorSaturationTest`.
- Tracking/reconciliation queue: `PaperTrackingCoordinatorSaturationTest`.
- Natural-access debounce: `BoundedDebounceRegistryTest` plus production wiring in `PaperTrackingCoordinator`.
- Destructive confirmations: `DestructiveConfirmationRegistryTest`.
- Administrative result pages: `PageRequestTest` and SQLite administration query tests.
- The deterministic profile also records the configured synthetic queue high-water mark and fails if it exceeds capacity.

## Migrations and storage integrity

- `MigrationUpgradeMatrixTest` constructs and upgrades every committed schema V1 through V7 through the production migration runner while checking durable identity/audit/pending/deleted-marker/campaign state.
- `MigrationRunnerTest` and `MigrationUpgradeMatrixTest` cover interrupted migration rollback/retry, integrity/foreign-key behavior, WAL/busy-timeout configuration, and required indexes.
- `SQLiteStorageRuntimeTest` covers bounded runtime close behavior.

## Reload/shutdown lifecycle

- `AtomicConfigurationTest` covers atomic replacement behavior.
- `SQLiteStorageRuntimeTest` covers bounded storage drain/close.
- `SQLiteDistributionCampaignRestartTest` proves durable campaign state survives process reconstruction.
- `tools/test_wp04_release_contract.py` pins the production plugin's bounded lifecycle queue, abort-on-saturation policy, stopping/unavailable transition, service unregister, pending-reload failure, configured database drain timeout, and guarded atomic reload path.

## Stable API

- `LoreItemsServiceV1AbiTest` pins API version, public method descriptor, result record components, status set, and a source consumer using only the public v1 surface.
- `FoundationLoreItemsServiceTest` covers durable acceptance/replay and service result mapping, including unavailable/validation/unknown-definition outcomes.
- `docs/public-api.md` is the integration contract. Command dispatch is not an integration boundary.

## Fixed profile

`tools/wp04_profile.py` uses a deterministic dataset and records environment/configuration, dataset digest, elapsed time, throughput, queue high-water mark, database latency distribution, and bounded main-thread-style snapshot task distribution for exactly:

- 100 simulated online players and 25,000 tracked instances;
- 5,000 container/display scopes with nested samples;
- 10,000 pending mixed mutations;
- 10 campaigns of 2,000 recipients each with mixed states;
- 100 simultaneous paginated administration queries.

It fails on a main-thread-style task over 50 ms, p99 main-thread-style task over 10 ms, queue overflow, incomplete fixed datasets, or source-boundary violations. CI runs it on the exact PR/main head and uploads the raw JSON result. This is automated profile evidence, not a substitute for WP-05 live-server timings.

## RC packaging and reproducibility

- Project version is `1.0.0-rc.1`.
- All Gradle archives disable preserved timestamps and use reproducible file ordering.
- CI builds the shaded jar, verifies required plugin/config/API/migration classes, emits a SHA-256 checksum, CycloneDX 1.5 JSON SBOM, Gradle runtime dependency manifest, and normalized entry-content manifest.
- CI performs a second clean shaded build and requires both the normalized entry manifest and jar checksum to match.
- `.github/workflows/release-rc.yml` repeats clean verification/profile/reproducibility from the exact tag and creates prerelease `v1.0.0-rc.1` targeted at `GITHUB_SHA` with the jar, checksum, SBOM, dependency manifest, normalized manifest, profile JSON, test reports, and committed release notes.

## Quality/review gates

- `.github/workflows/ci.yml` runs `gradle --no-daemon clean check`, repository-tooling tests, new-code complexity, exact-head Codacy, fixed profile, package smoke, and clean-build reproducibility.
- PR review threads must be empty/resolved and the final exact-head checks must be successful before normal merge.
- After merge, the exact `main` merge commit must pass CI. Only then may tag/release publication run.
- WP-04 becomes `COMPLETE` only after the prerelease target SHA and all required assets/checksums are verified. WP-05 remains blocked before that point.
