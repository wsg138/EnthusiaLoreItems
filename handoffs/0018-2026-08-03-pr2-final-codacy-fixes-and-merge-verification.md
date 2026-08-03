# PR #2 final Codacy fixes and merge verification

Date: 2026-08-03

## Scope and starting state

This report covers only PR #2, **Foundation and durable core**, on branch `agent/loreitems-pr1-foundation`. No Creation, Adoption, physical Direct Delivery execution, Protection, command, listener, tracking/reconciliation execution, GUI, editing, group-file/campaign execution, physical deletion, EnthusiaTags integration, or other later phase was started.

Live state at the beginning of this session:

- `main`: `42aac09129c4fbda2756d30ee034c27ed1cf85b4`
- PR #2 head: `5773744fe302becfb2a43d30cabc951b3c000eaf`
- PR: open, ready for review, mergeable, and unmerged

The earlier handoff stated that Codacy was at zero, but live GitHub state took priority. Codacy had reanalyzed the documentation head and reported four medium findings.

## Codacy evidence retrieval

The detailed records were obtained from GitHub's Codacy check-run annotations rather than guessed from the aggregate PR comment.

Evidence path:

1. Inspected the Codacy PR summary and live GitHub PR comment.
2. Inspected the exact Codacy check run and its annotations through GitHub.
3. Used the repository's temporary evidence-export workflow to export check runs, combined status, and all Codacy annotation pages for target `242eccf028939fb8cd865f9943e6dbca1661bcc4`.
4. Downloaded workflow artifact `8845571191` from run `30786972802`, job `91602287961`.
5. Verified artifact digest `sha256:a7c0baf22d11831c70b47faa379d64677174c3ea16e4d18cd7356deddcb90a8c`.
6. Read Codacy check run `91602164997`, which concluded `action_required` with four annotations.

The annotation API exposed file, line, warning level, and message, but not a stable analyzer rule identifier. The rule descriptions below therefore use the exact message and identifiable rule family rather than inventing an unavailable rule ID.

## Retrieved findings and classifications

| File | Line | Severity | Rule / exact message | Classification | Resolution |
| --- | ---: | --- | --- | --- | --- |
| `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDefinitionRepositoryTest.java` | 240 | Medium / warning | Field/method name collision — `Field statementText has the same name as a method` | Legitimate test-code clarity issue | Renamed the enum backing field and constructor parameter to `sqlText`; retained the descriptive `statementText()` accessor. |
| `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDeletedDefinitionMarkerRepositoryTest.java` | 224 | Medium / warning | Field/method name collision — `Field statementText has the same name as a method` | Legitimate test-code clarity issue | Renamed the enum backing field and constructor parameter to `sqlText`; retained the accessor. |
| `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepositoryTest.java` | 253 | Medium / warning | Field/method name collision — `Field statementText has the same name as a method` | Legitimate test-code clarity issue | Renamed the enum backing field and constructor parameter to `sqlText`; retained the accessor. |
| `domain/src/main/java/net/enthusia/loreitems/domain/InstanceAnomaly.java` | 74 | Medium / warning | Exhaustive switch — `Switch statements should be exhaustive, add a default case (or missing enum branches)` | Legitimate production-code defensive-validation issue | Added a fail-closed `default` branch that throws for an unsupported anomaly status. |

All four retrieved findings were legitimate and in scope. None was classified as a false positive, generated-code noise, documentation noise, or analyzer/configuration problem.

## Suppressions and analyzer configuration

No new suppression was added for these findings. No analyzer was disabled or weakened. The existing narrow suppressions and the SQLite migration exclusion documented in reports 0015–0017 were unchanged.

Temporary evidence and source-export workflows used during verification were removed before the clean code head.

## Clean code head and exact diff

The clean post-fix code head was:

- `d3b8832ccc8370f97789cd2b7738626b76e27963`

Compared with the prior final-report head `242eccf028939fb8cd865f9943e6dbca1661bcc4`, the net code changes were limited to four files:

- `SQLiteDefinitionRepositoryTest.java`: backing-field rename
- `SQLiteDeletedDefinitionMarkerRepositoryTest.java`: backing-field rename
- `SQLiteDirectDeliveryRepositoryTest.java`: backing-field rename
- `InstanceAnomaly.java`: fail-closed default switch branch

No later-phase implementation was added.

## Local full verification

An exact source bundle of the corrected branch was combined with the retained Gradle 8.14.3 distribution and dependency cache. The temporary source-export workflow file was removed from the local tree so the checked source matched the clean code head.

Command:

```text
GRADLE_USER_HOME=/tmp/local-gradle-bundle/gradle-home \
  /tmp/local-gradle-bundle/gradle-8.14.3/bin/gradle \
  --offline --no-daemon clean check --rerun-tasks
```

Result:

```text
BUILD SUCCESSFUL in 19s
34 actionable tasks: 26 executed, 8 up-to-date
```

## Exact-head GitHub Actions evidence

Exact clean-code-head CI:

- PR head: `d3b8832ccc8370f97789cd2b7738626b76e27963`
- workflow run: `30788311865`
- job: `91606345899`
- tested merge ref: `14644c4ce952fc73f99de823b09c39e8ca80b791`
- merge base: `42aac09129c4fbda2756d30ee034c27ed1cf85b4`
- Java: Temurin `21.0.11+10`
- Gradle: `8.14.3`
- command: `gradle --no-daemon clean check`
- result: `BUILD SUCCESSFUL in 1m 13s`
- tasks: `34 actionable tasks: 26 executed, 8 up-to-date`

Only runner/action Node deprecation warnings were present; the build and all checks succeeded.

## Clean-code-head Codacy evidence

Codacy PR comment `5160613107` updated at `2026-08-03T05:51:28Z`, after clean code head `d3b8832ccc8370f97789cd2b7738626b76e27963` was created at `2026-08-03T05:50:47Z`, and reported:

- **Up to standards**
- **0 issues**
- **0 new issues**

The final documentation head created by this report, `CURRENT.md`, and `INDEX.md` must repeat both exact-head GitHub Actions and Codacy gates before merge.

## Harsh full-PR review

A separate harsh review was performed against live code and the complete PR scope, with emphasis on the required production risks.

- **Item loss and duplicate delivery:** external delivery acceptance remains a single transaction; external operation IDs replay durable outcomes; uniqueness and rollback tests cover duplicate requests and failed final inserts.
- **Transaction rollback:** transaction handling remains centralized; rollback and auto-commit restoration failures are preserved as suppressed exceptions; mutation-plus-audit tests verify atomic rollback.
- **Stale claims:** direct-delivery and pending-mutation transitions require the expected state, matching claim token, and a live lease; expired claims are moved to review in deterministic bounded batches.
- **Lifecycle races:** startup and service/configuration publication are serialized against disable; queued reload futures are completed during shutdown; writable state is not republished after stopping begins.
- **Main-thread blocking:** storage and migration work remain on owned bounded workers; Bukkit item codecs explicitly require primary-thread ownership.
- **Unbounded work:** database and lifecycle queues are bounded; pagination and recovery limits are enforced; no unbounded startup recovery was introduced.
- **Mutable Bukkit references:** identity/template codecs clone source items before mutation, clear identity from templates, fail closed, and force amount/max-stack size to one.
- **Migration safety:** migration V1 remains versioned, transactional, classpath-loaded, and guarded by schema history.
- **Phase leakage:** no physical inventory insertion, Creation/Adoption, Protection, command, listener, GUI, editing, campaign execution, deletion execution, or Tags integration was added.

No new confirmed defect or merge blocker was found in this review. The four Codacy fixes did not weaken architecture, durability, thread safety, bounded-work rules, tests, or compiler warnings.

## Review state and limitations

At the clean code head:

- submitted reviews: none
- unresolved review threads: none
- CodeRabbit: no code review finding; review remained skipped because the PR exceeded its file limit and capacity was unavailable
- live Paper/Leaf server: not run

Do not infer real-server PDC serialization, reload/shutdown, corrupt-database recovery, backup/restore, or physical inventory-delivery evidence from unit or CI checks.

## Authorized completion procedure

After the final documentation head passes exact-head GitHub Actions, Codacy remains at zero, and reviews/threads remain clear, PR #2 is authorized for a normal merge commit. After merge, verify the resulting `main` SHA and available checks, attempt feature-branch deletion when supported, and stop without beginning another phase.
