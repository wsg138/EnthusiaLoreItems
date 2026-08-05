# WP-06 — EnthusiaTags service-API integration

## Objective

After the production LoreItems release, complete the separate `wsg138/EnthusiaTags` integration that grants lore-item rewards through the released versioned Bukkit service API with durable end-to-end idempotency and safe unavailable/restart behavior.

## Dependencies

- WP-05 is `COMPLETE`.
- `wsg138/EnthusiaLoreItems` release `v1.0.0` is verified and its stable service API/documentation are available.
- Live `main`, open PRs, current architecture/requirements, reward persistence, and claim identity in `wsg138/EnthusiaTags` are reconciled before work.

## Complete required scope

1. Work primarily in `wsg138/EnthusiaTags`. Add a dedicated `LORE_ITEM` reward action and configuration model containing the LoreItems definition lookup key. Validate missing/blank/unknown action fields without dispatching a command or marking a reward delivered.
2. Discover the released `LoreItemsServiceV1` through Bukkit's services manager. Do not compile against plugin implementation classes, access LoreItems SQLite directly, or dispatch `/loreitems` commands.
3. Use the immutable EnthusiaTags reward-claim identity as the external operation ID. The same claim/reward attempt must always produce the same key across retry, plugin reload, and server restart; distinct claims must not collide.
4. Queue the LoreItems delivery asynchronously through the service with definition key, authoritative player UUID, and operation key. Do not block the server thread waiting for completion.
5. Map durable outcomes exactly:
   - `ACCEPTED_QUEUED`: persist the Tags reward as durably handed off according to Tags' claim state model;
   - `ALREADY_ACCEPTED`: treat as idempotent success without a second reward;
   - `UNKNOWN_DEFINITION` or `VALIDATION_FAILURE`: retain a visible failed/review state and do not mark delivered;
   - `SERVICE_UNAVAILABLE`, missing plugin, timeout, plugin reload, or transient exception: retain/requeue the claim and do not mark delivered.
6. Persist Tags-side intent/state before or atomically with marking the reward handoff. A crash between LoreItems acceptance and Tags persistence must retry with the same key and receive idempotent success rather than duplicate the item.
7. Resume pending lore-item rewards after either plugin reloads, either plugin enables in either order, or the server restarts. Register/unregister service availability safely and use bounded retries/backoff/queues.
8. Add operator-visible audit/status information identifying Tags claim, reward action, definition key, external operation key in privileged diagnostics, current handoff state, LoreItems outcome, attempts, and last error. Do not expose LoreItems internal instance UUIDs to players.
9. Add configuration examples, a permission for every new privileged status/retry action, a configured message for every operator-visible result, a `softdepend: [EnthusiaLoreItems]` plugin declaration while still using runtime service discovery, installation/version compatibility, recovery instructions, and cross-plugin staged deployment/rollback documentation.
10. Upgrade the EnthusiaTags pull-request workflow so `mvn --batch-mode --no-transfer-progress clean test package` and an exact-head Codacy verification step both run against the current PR head, with the same stale-head rejection rule used by LoreItems.
11. If the released LoreItems API contract itself is reproducibly defective, do not invent a command/database workaround. Keep WP-06 `BLOCKED`, record the exact blocker in GitHub, repair it under the same WP-06 assignment using branch `agent/wp-06-loreitems-api-blocker` and PR title `WP-06: repair released LoreItems API blocker`, publish the required compatible LoreItems patch release, then resume the original Tags branch. This is one fixed package with an explicitly defined blocker path, not an ad hoc subpackage.

## Exact acceptance criteria

- A configured `LORE_ITEM` reward queues one LoreItems instance for the target UUID through the service API and never command dispatch.
- Repeating the same claim before/after acceptance, Tags persistence, either plugin reload, or restart yields one LoreItems request/instance because the external operation key is stable.
- Missing/unavailable/read-only LoreItems never causes Tags to mark the reward delivered; recovery succeeds after service availability returns.
- Unknown definition and validation failures remain visible and require correction/retry rather than silent success or endless unbounded retry.
- Plugin enable order is irrelevant; no hard crash occurs when LoreItems is absent.
- Main-thread work is bounded; service stages are handled asynchronously; retry queues and audit queries are bounded/paginated.
- Cross-plugin documentation identifies supported LoreItems API/release versions and rollback order.

## Required automated tests

- Tags domain/application tests for `LORE_ITEM` parsing, claim-derived operation-key stability/collision resistance, outcome mapping, and state transitions.
- Adapter tests with a fake Bukkit service for accepted, already accepted, unknown definition, validation failure, unavailable/read-only, timeout, exception, service registration/removal, and plugin enable-order changes.
- Persistence/restart tests for crashes before call, after LoreItems acceptance/before Tags commit, after Tags commit, retry, reload, and bounded backoff.
- End-to-end integration test using the released LoreItems API artifact/service proving one physical request for repeated claim attempts.
- Architecture tests preventing command dispatch, LoreItems implementation/database dependencies, and main-thread blocking.
- Full `mvn --batch-mode --no-transfer-progress clean test package`, exact-head EnthusiaTags Codacy verification, and any cross-repository compatibility test required by the API-blocker path.

## Required review and verification gates

- Independent review in `wsg138/EnthusiaTags` focused on cross-plugin idempotency, claim identity, outcome semantics, enable/reload/restart order, threading, bounds, dependency isolation, and evidence accuracy.
- Exact-head EnthusiaTags Build workflow and exact-head Codacy verification; no requested changes and zero unresolved threads.
- Cross-plugin integration evidence identifies exact LoreItems release/API artifact and exact Tags head SHA.
- Normal merge commit into EnthusiaTags `main`, followed by live `main` verification. Any API-blocker PR also requires its own exact-head gates, compatible patch release, and normal merge before Tags work resumes.

## Explicit exclusions

- Rebalancing reward rarity, values, or economy; the implementation plan requires that product change to remain separate from the API integration.
- Command dispatch, direct LoreItems database access, internal instance-ID handling, or duplicate compensation by deleting items.
- New LoreItems gameplay functionality.
- Any package after WP-06; this is the final fixed package.

## Definition of complete

WP-06 is complete only when the full Tags integration, persistence/recovery, tests, documentation, cross-plugin evidence, and reviews pass; any explicit API blocker is repaired and released under the same package; the Tags PR is normally merged and `main` verified; final workflow state shows 6/6 complete and 100%; and the worker stops.

## Expected status transitions

`BLOCKED -> READY -> IN_PROGRESS -> IN_REVIEW -> VERIFYING -> MERGED -> COMPLETE`

A released-API blocker changes `IN_PROGRESS -> BLOCKED -> IN_PROGRESS` after the explicit blocker path is resolved. No new package is created.

## Branch and PR naming

Primary integration in `wsg138/EnthusiaTags`:

- Branch: `agent/wp-06-loreitems-integration`
- PR title: `WP-06: integrate EnthusiaTags with LoreItems service API`

Only for the explicit released-API blocker in `wsg138/EnthusiaLoreItems`:

- Branch: `agent/wp-06-loreitems-api-blocker`
- PR title: `WP-06: repair released LoreItems API blocker`

## Exact next package

None. WP-06 is the final fixed package. After completion the queue is 6/6 complete and weighted progress is 100%.
