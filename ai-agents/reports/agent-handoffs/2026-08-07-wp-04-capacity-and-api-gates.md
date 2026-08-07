# WP-04 capacity and API checkpoint

## Package
- WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Live base `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Section implementation head before this checkpoint: `b69abe60b5c59e0de844502ad39ed354652726df`

## Completed in this coherent section
- Added deterministic database executor saturation coverage proving configured queue capacity, explicit rejection beyond capacity, depth/rejection/duration metrics, and no abandoned queued future after forced shutdown.
- Added deterministic template-update scan and retry backlog saturation coverage, including ready/deferred/rejected tiers, deduplication, freed-slot reuse, and budget-derived capacity clamping.
- Added deterministic reconciliation/tracking coordinator saturation coverage proving one in-flight plus eight queued at budget one, explicit rejection of the tenth unique request, and accepted/rejected/completed/depth/duration metrics.
- Added deterministic template mutation/destructive-first coordinator saturation coverage proving its one-in-flight plus eight-queued bound and scheduled-instance deduplication.
- Added stable Bukkit V1 service tests for durable outcome mapping, external idempotency-key preservation/replay, validation failure, and unavailable storage.
- Added V1 source/ABI shape tests that pin the API version, service method signature, result record components, and status names.
- Added `docs/public-api.md` with durable-acceptance semantics, caller-owned replay keys, ServicesManager lookup/unavailable handling, async use, no command-dispatch integration, and the V1 compatibility policy.
- Investigated the architecture-required debounce map. A generic helper was briefly added, then deliberately removed because it was not connected to a real event/work path. Dead compliance code is not being counted as acceptance evidence.

## Verification
- Exact head `bbbbcd9d2f7304493a6bb8ddbdd00582708d8154` (all new saturation/API tests and docs, before removal of the unwired debounce helper/test) passed GitHub Actions run `31194168975`:
  - `gradle --no-daemon clean check`: success
  - repository tooling: success
  - new-code complexity: success
  - exact-head Codacy: success
- The subsequent two commits only remove the unused debounce helper and its isolated test. Exact-head CI for the post-removal head must still be observed before this section is treated as verified at the current head.
- No local build result is claimed; the local runtime cannot resolve GitHub dependencies.

## Findings
- The architecture requires an expiry- and size-capped debounce map, but no live production debounce implementation was found. This remains an explicit WP-04 gap.
- Several WP-04 named queues are intentionally durable database claim windows rather than independent in-memory queues; saturation evidence must test their actual claim/page/budget bounds rather than inventing redundant executor queues.
- Distribution campaign file work uses a bounded executor, but rejection behavior at `CompletableFuture.supplyAsync` call sites still needs harsh-review/saturation verification to ensure overload cannot escape synchronously, hang, block the server thread, or report false success.

## Remaining criteria
- Complete saturation/backpressure verification for delivery/durable claim workers, campaign executor/work, notification coalescing, GUI/admin query semaphores and pagination, a real wired debounce path, cache limits, retry/defer paths, and useful overload signals.
- Complete remaining deterministic crash/restart matrix and fixed-seed randomized recovery across all durable state machines and reload/shutdown points.
- Lifecycle reload/shutdown automation.
- Complete API reload/restart verification in addition to the source/ABI and outcome tests now present.
- Reproducible fixed-scenario performance/profile harness and threshold evidence.
- Remaining static-analysis/complexity remediation as new work is added.
- RC `1.0.0-rc.1` packaging, CycloneDX JSON SBOM, dependency manifest, checksum, reproducibility/package smoke, release workflow/notes.
- Full-package harsh review and fixes; exact-head CI/Codacy and zero unresolved review state.
- Normal merge, post-merge `main` verification, and verified `v1.0.0-rc.1` prerelease/assets.

## Blocker
None. WP-04 remains `IN_PROGRESS`.

## Exact next action
Verify the post-removal exact head, then continue the same saturation/backpressure gate by exercising the delivery claim bounds, notification coalescing, administration query limits/pagination, distribution executor rejection behavior, and a real wired debounce path. Do not start WP-05.
