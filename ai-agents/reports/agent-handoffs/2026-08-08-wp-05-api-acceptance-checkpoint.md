# WP-05 public API acceptance checkpoint — 2026-08-08

## Package state
- Package: WP-05 — live acceptance and production release.
- Status remains `IN_PROGRESS`.
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Draft PR: #18.
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- Tested acceptance head for this section: `e8921e0d3b633bc4a8c803aa37898acfeea2747e`.

## Completed acceptance criteria
Permanent exact-tested PASS coverage now includes 8 of 35 cases:
- `ACC-ID-001`
- `ACC-ID-002`
- `ACC-CORE-001`
- `ACC-CORE-002`
- `ACC-CORE-003`
- `ACC-CORE-004`
- `ACC-CORE-005`
- `ACC-API-001`

New permanent evidence:
- `docs/wp-05-acceptance/ACC-API-001/README.md`
- `docs/wp-05-acceptance/ACC-API-001/evidence-summary.json`

## ACC-API-001 verification
Dedicated live run `31242418203` on `e8921e0d3b633bc4a8c803aa37898acfeea2747e`: **success**.
- Paper 1.21.11 build 116 / Temurin 21.0.11+10.
- Plugin JAR SHA-256 `e817b066ce18daca3556b83e20828ad40d45257f2c75e03b5ee4e43221820dd1`.
- Artifact ID `9017460902`, digest `sha256:16e727aa54bb30c5fe29b2f75f53b27659be1ab8f3a140d524b4369f1e6adfbc`.
- Unique operation accepted; pre-restart and post-restart replay returned `ALREADY_ACCEPTED` without duplicate durable intent.
- Unknown definition returned `UNKNOWN_DEFINITION` with no delivery.
- Invalid definition key returned `VALIDATION_FAILURE` before durable request persistence.
- Independent SQLite `BEGIN IMMEDIATE` contention produced `SERVICE_UNAVAILABLE`; retry of the same operation ID after recovery was accepted exactly once.
- Both accepted deliveries reached `COMPLETED`, attempt count 1.
- Final SQLite integrity `ok`; foreign-key check empty.
- Exact runtime migration path is schema V7. The artifact's `PRAGMA user_version=0` is non-authoritative because LoreItems uses `schema_history`; the permanent evidence records the exact migration-path basis.

Same tested head also passed:
- Java identity/core run `31242418202`.
- Full-inventory run `31242418225`.
- Floodgate identity run `31242418201`.
- CodeRabbit combined status: success.

The general CI run `31242418204` had already passed Gradle `clean check`, repository tooling, and new-code complexity and was waiting in the exact-head Codacy/full WP-04 verification path when this documentation checkpoint was prepared. This checkpoint must not be treated as the final package verification gate; all final-head gates remain mandatory.

## Findings and fixes
No LoreItems production defect was found in `ACC-API-001`.

Harness-only finding: first run `31242194001` on `3477fc6103441573ccf628aacce92d99615d2bd4` reached the intended live API sequence but its final parser incorrectly assumed unknown-definition outcomes were not persisted and incorrectly expected `external:`-prefixed direct-delivery idempotency keys. Commit `e8921e0d3b633bc4a8c803aa37898acfeea2747e` corrected only the acceptance assertions and strengthened per-line status matching. The corrected run passed.

## Remaining acceptance criteria
27 current exact-head cases remain before the mandatory final all-35 repetition:
- `ACC-ENV-001`
- `ACC-EDIT-001..003`
- `ACC-TRACK-001..003`
- `ACC-PROT-001..002`
- `ACC-ANOM-001..002`
- `ACC-DEST-001..004`
- `ACC-DIST-001..005`
- `ACC-LIFE-001..002`
- `ACC-OPS-001..005`

Package-level gates still remain unchanged: fix/regress every confirmed defect; repeat all 35 cases on the exact final JAR after the last code change; rerun all WP-04 automated gates; finalize `1.0.0` release evidence and upgrade/backup/rollback rehearsals; independent harsh code review plus separate evidence audit; owner/operator sign-off; normal merge; live-main verification; and production `v1.0.0` tag/assets/checksum verification.

## Review state
No submitted review or unresolved review-thread finding was present at the start of this section. The active PR remains draft; final review/evidence-audit gates have not begun.

## Blocker
None. WP-05 remains executable and must not be split.

## Exact next action
Execute `ACC-DEST-001` end to end on a disposable Paper server using one definition with two physically accessible sibling instances. Preview and confirm exact removal of only one hidden instance, verify the durable operation/target reaches terminal verified removal, verify the sibling remains active and physically present, restart, repeat DB/physical assertions, sanitize hidden instance UUIDs from permanent evidence, and checkpoint. Any mismatch remains WP-05 work on this branch.
