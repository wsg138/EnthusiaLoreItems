# Handoff 0016 — PR #2 Codacy evidence blocker

Date: 2026-08-02 (America/Indiana/Indianapolis)

## Scope and outcome

This session remained limited to PR #2, **Foundation and durable core**. No Creation, Adoption, Direct Delivery execution, Protection, command, listener, GUI, tracking/reconciliation execution, physical deletion, Tags integration, or other later-phase implementation was started.

The branch was not merged. All Codacy findings that could be retrieved with file-level evidence were classified and remediated, but Codacy still reports 35 new issues while withholding their file, line, rule, severity, and message from every available authenticated GitHub surface. The owner instruction explicitly forbids guessing or merging in that state.

## Reconciled starting state

- Starting `main`: `42aac09129c4fbda2756d30ee034c27ed1cf85b4`
- Starting PR #2 head: `a4476d9ff3f01f2a9beda2304ae1d43eca5e0c2f`
- Starting PR state: open, ready for review, mergeable
- Starting exact-head GitHub Actions: run `30782103396`, job `91588685316`, successful
- Starting submitted reviews: none
- Starting unresolved review threads: none
- CodeRabbit: review skipped because the PR exceeded the 100-file limit and review capacity was unavailable

Live GitHub state superseded stale handoff pointers. `CURRENT.md` was corrected to reference the actual immutable reports and `docs/architecture.md` rather than nonexistent paths.

## Codacy evidence retrieved

### First evidence set — 133 aggregate / 50 detailed

The first GitHub annotation export exposed 50 records from an aggregate of 133. Their exact file, line, rule, severity, message, classification, and resolution are recorded in [report 0015](0015-2026-08-02-pr2-codacy-remediation-and-merge-readiness.md).

### Second evidence set — 85 aggregate / 50 detailed

A clean target head, `91fcce515498c3a703edda354fb360c66184b0a7`, reported 85 new issues. GitHub check-run annotation export returned 50 detailed records. They covered:

- unpinned GitHub Actions revisions;
- SQL-construction and test-fixture SQL rules;
- field/method name collisions;
- repeated validation literals;
- transaction-finally behavior;
- oversized or complex repository tests and methods;
- generated/test-only noise;
- SQLite migration rules from non-SQLite or organization-specific policies.

Every legitimate production, workflow, and test issue in the retrieved records was fixed. Test refactors preserved assertions and restart/transaction coverage.

### Third evidence set — 48 aggregate / 36 detailed

After the prior fixes and the narrow migration exclusion, Codacy reported 48 new issues. Artifact `8845267204` from run `30786013952`, job `91599573242`, exposed 36 detailed records. The artifact digest was `sha256:23a5150971aaf97699fdd75ee3379f88d07bad07d4b99224504169b63389ea28`.

The records were:

| Area | Retrieved issue |
| --- | --- |
| SQLite repository tests | three `sql` enum fields matched methods of the same name |
| `SQLiteUnitOfWorkTest` | test-only closed SQL fixture flagged as injection |
| `AtomicConfiguration` | field `current` matched method `current()` |
| application/domain records | repeated conditional-validation literals |
| `ItemCodecException` | field `failure` matched method `failure()` |
| `HexagonalArchitectureTest` | PMD did not recognize ArchUnit `@ArchTest` fields as tests |
| `InstanceAnomaly` | constructor validation complexity |
| `FoundationConfigurationLoader` | local `HashMap` concurrency warning, repeated separator, J2EE classloader warning |
| `LoreItemsPlugin` | five J2EE thread warnings and `initialize` size/complexity/NPath findings |

All 36 retrieved findings were classified and resolved. The remaining 12 aggregate findings were not exposed in that pass.

### Fourth evidence set — 35 aggregate / zero detailed

After the third remediation pass, Codacy reported:

- 35 new issues total;
- 1 critical Security;
- 5 high Compatibility;
- 2 high and 22 medium ErrorProne;
- 4 medium Complexity;
- 1 high Performance.

The clean analyzed head was `39b48dffc0552e3f145fd530530d7ac184b322d9`.

Retrieval attempts:

1. GitHub pull-request comment: aggregate categories only.
2. GitHub combined status: no Codacy status record or target issue metadata.
3. GitHub check runs for the exact target SHA: no Codacy check run; therefore no annotations.
4. Temporary target-check export workflow: artifact `8845378339`, run `30786384920`, job `91600598266`, digest `sha256:d3764f9485e36db01b5a40ee5de35dd7fa4dff9a614cbae8778ebaa13376f672`; exported zero Codacy check runs and zero annotations for the target SHA.
5. Codacy pull-request page: link identified, but detailed records require authenticated Codacy access.
6. Official Codacy API endpoint: `GET /api/v3/analysis/organizations/gh/wsg138/repositories/EnthusiaLoreItems/pull-requests/2/issues?limit=100`.
7. Temporary API evidence workflow tested both `CODACY_API_TOKEN` and `CODACY_PROJECT_TOKEN`. Artifact `8845405420`, run `30786470456`, job `91600831553`, digest `sha256:47174d7c2fae45f8c63e8513a22e01aacb90a963171c4a570ae5e18ad280c4e3`, recorded `authentication: none`, `httpStatus: 000`, and `No Codacy API token secret is available to this workflow.`
8. Repository configuration and locally reproducible Gradle analyzers were inspected. The remaining Codacy server-side records were not available from them.

No broad suppression was added for these 35 unknown records, and no speculative code changes were made for them.

## Files and rules remediated

The Codacy remediation changed the following areas after the first clean-head baseline:

- `.github/workflows/ci.yml`: pinned checkout, setup-java, and setup-gradle actions to exact revisions.
- `.codacy.yml`: excluded only `adapters-sqlite/src/main/resources/db/migration/V1__foundation.sql` because Codacy applied non-SQLite dialect rules and organization-specific `RAC_*` table policy to the immutable SQLite migration.
- Paper codec and tests: validation literal and test naming cleanup.
- SQLite repositories: explicit query-fragment helpers, conditional-literal cleanup, transaction-finally safety, and naming cleanup.
- SQLite tests: scenario/helper extraction, naming cleanup, fixture constants, and narrow test-only SQL annotations without reducing assertions.
- Application/domain records: named validation constants and field/method collision fixes.
- `InstanceAnomaly`: validation helpers replacing a high-complexity constructor switch.
- `FoundationConfigurationLoader`: resource lookup, named separators/comments, and thread-confined local-map documentation.
- `LoreItemsPlugin`: lifecycle initialization decomposition while preserving publication/shutdown serialization.
- Architecture test: narrow ArchUnit test-discovery suppression.

The exact post-baseline file list is available from the comparison `91fcce515498c3a703edda354fb360c66184b0a7..06a19db2f154ab69b4eae96f643bcaa0f9b7f7f0`.

## Narrow suppressions and justifications

- `@SuppressWarnings("PMD.DoNotUseThreads")` on the bounded database executor and plugin lifecycle owner: this is a Paper plugin, not a J2EE container; both executors are explicitly bounded, owned, and shut down.
- `@SuppressWarnings("PMD.UseConcurrentHashMap")` on thread-confined local maps: no map escapes its method/thread, so a concurrent map would add cost without safety.
- `@SuppressWarnings("PMD.TestClassWithoutTestCases")` on the ArchUnit test class: its `@ArchTest` fields are the test cases.
- `// nosemgrep` only on closed, fixed, test-only SQL fault-injection/fixture statements.
- `.codacy.yml` exact-path exclusion only for the immutable SQLite migration, with the dialect/policy mismatch documented in the file.

No analyzer was disabled repository-wide.

## Harsh-review findings and fixes

The independent review covered item-loss risk, duplicate delivery, transaction rollback, stale claims, lifecycle races, main-thread blocking, unbounded work, mutable Bukkit references, migration safety, and phase leakage.

Confirmed defects fixed during this completion effort:

1. Forced database shutdown could discard queued executor tasks without completing their futures. Discarded tasks now complete exceptionally, with a regression test.
2. Plugin disable could discard queued configuration reloads and leave returned stages incomplete. Pending reloads now complete with a stopping result.
3. Startup/reload publication could race disable and republish writable state after shutdown began. Publication and shutdown are serialized.
4. Expired direct-delivery and mutation claim recovery used unbounded updates. Recovery is ordered, limited, batch-tested, and startup uses the configured delivery batch size.
5. Duplicate transaction implementations could diverge and obscure rollback failure. Transaction handling was consolidated and finally behavior hardened.

No later-phase behavior was introduced. Physical inventory delivery remains deferred.

## Verification evidence

Local verification after the major remediation pass:

```text
GRADLE_USER_HOME=/tmp/local-gradle-bundle/gradle-home \
  /tmp/local-gradle-bundle/gradle-8.14.3/bin/gradle \
  --offline --no-daemon clean check --rerun-tasks
BUILD SUCCESSFUL in 13s
34 actionable tasks: 33 executed, 1 up-to-date
```

Earlier exact-head local verification also completed successfully in 14 seconds with all 34 actionable tasks executed.

Directly verified GitHub Actions evidence before the final unknown-Codacy blocker:

- initial exact head `a4476d9ff3f01f2a9beda2304ae1d43eca5e0c2f`: run `30782103396`, job `91588685316`, success;
- verification head used for the local bundle: run `30784414682`, job `91595182954`, success;
- clean pre-remediation head `91fcce515498c3a703edda354fb360c66184b0a7`: run `30784971850`, job `91596692908`, success.

Later runs were cancelled by subsequent documentation/evidence commits and are not claimed as passing exact-head evidence.

## Review status

- Submitted reviews: none at the last live inspection.
- Unresolved review threads: none at the last live inspection.
- CodeRabbit: no code finding; review skipped due to file-count limit/capacity.

These must be rechecked live before any eventual merge.

## Precise blocker and required owner export

Do not merge PR #2 until the remaining 35 Codacy records are supplied with details and resolved or narrowly classified.

Either of the following is sufficient:

1. From the Codacy PR #2 Issues tab, filter to **New issues** and export/provide all 35 records with:
   - issue ID;
   - file path;
   - start/end line;
   - tool;
   - pattern/rule ID and title;
   - severity;
   - category;
   - full message/description.

2. Create a Codacy account or repository API token and expose it to the repository workflow as `CODACY_API_TOKEN` or `CODACY_PROJECT_TOKEN`, then rerun the official pull-request issues endpoint above and provide its complete paginated JSON response.

Until that evidence exists, the exact status is **blocked, not safe to merge**.
