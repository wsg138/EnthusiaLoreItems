# Agent workflow rules

## Authority order

Agents resolve conflicts in this order:

1. live GitHub state for the repository being changed;
2. the selected work-package contract;
3. `WORK-QUEUE.md` and this file;
4. `REQUIREMENTS.md`;
5. `docs/architecture.md`;
6. `docs/implementation-plan.md`;
7. `WORKSPACE-STATE.md` and `reports/agent-handoffs/latest.md`;
8. older handoffs, chat messages, local notes, and assumptions.

A committed snapshot may be stale. A live merge, branch head, open or draft pull request, review, thread, check, status, or workflow run cannot be overridden by older text.

## Fixed-package invariant

The remaining program contains exactly six packages: WP-01 through WP-06. Their identities, order, dependencies, weights, scope, acceptance criteria, branch names, and pull-request titles are fixed by the committed package files.

- Every implementation worker automatically selects or resumes exactly one whole package.
- No operator-supplied package ID is required or accepted as a substitute for live routing.
- A worker cannot choose a convenient subset.
- No `WP-01a`, `WP-01b`, phase, follow-up package, cleanup package, substitute package, or equivalent subdivision may be created.
- Internal commits, checklists, or implementation sequences do not change the package boundary.
- Newly discovered defects inside the selected scope are fixed in the same package.
- Review findings, CI failures, and interruptions return to the same canonical branch and package.
- Scope changes require an explicit owner-approved amendment in a dedicated planning PR; an implementation worker cannot redefine a package in its implementation PR.

## Roles

### Planning/workflow agent

Maintains only the fixed workflow documents, reconciles stale state, and audits loopholes. It does not implement package functionality.

### Implementation worker

Reconciles live GitHub, automatically selects or resumes exactly one package, owns the whole package through review, merge, and required post-merge verification, and then stops.

### Independent reviewer

Reviews the complete package against its contract rather than only the apparent intent of the diff. The reviewer explicitly examines item loss, duplicate creation, destructive ambiguity, main-thread work, unbounded work, reload/shutdown, persistence/recovery, architecture boundaries, and evidence accuracy.

### Live-acceptance operator

Runs the WP-05 matrix on the designated Paper/Leaf server and supplies reproducible GitHub-backed evidence. Failures are not accepted or deferred; confirmed defects remain in WP-05 until fixed and retested.

## Exclusive status model

Only these package statuses are valid:

- `BLOCKED`: a verified external dependency prevents further progress. Ordinary implementation difficulty, a failing test, review feedback, unavailable time, or an interrupted worker is not a blocker.
- `READY`: every dependency is verified complete on live GitHub and no unfinished canonical branch or PR exists for the package.
- `IN_PROGRESS`: the exact package branch or PR is active and the package is being claimed, resumed, or implemented.
- `PARTIAL`: useful work is committed and resumable on the canonical branch, but acceptance criteria remain and no verified external dependency prevents progress.
- `IN_REVIEW`: all required implementation and test scope is present, but required review is unfinished.
- `VERIFYING`: review findings are resolved and exact-head gates are running or being inspected, including package-specific post-merge or release verification when applicable.
- `COMPLETE`: the package PR was normally merged and every required live `main`, release, or package-specific post-merge verification is complete.

`PARTIAL`, `IN_PROGRESS`, `IN_REVIEW`, and `VERIFYING` receive zero official weighted completion credit. The legacy transition words `MERGED` and `EVIDENCE_PENDING` that may appear in package-contract examples are not queue statuses: a merged-but-unverified package remains `VERIFYING`, and WP-05 evidence collection remains `IN_PROGRESS` unless an intentional stop requires `PARTIAL` or a verified external dependency requires `BLOCKED`.

Normal transitions are:

- `BLOCKED -> READY` after the dependency is verified complete;
- `READY -> IN_PROGRESS` after a successful canonical claim;
- `IN_PROGRESS -> PARTIAL` before an intentional unfinished stop;
- `PARTIAL -> IN_PROGRESS` when the same branch is resumed;
- `IN_PROGRESS -> IN_REVIEW` when all implementation and required test scope is present;
- `IN_REVIEW -> IN_PROGRESS` or `PARTIAL` for findings;
- `IN_REVIEW -> VERIFYING` after review is complete;
- `VERIFYING -> IN_PROGRESS` or `PARTIAL` for failed or stale gates;
- `VERIFYING -> COMPLETE` only when the prospective final-state commit is normally merged and live verification succeeds.

## Automatic routing protocol

Every worker performs this routing before changing implementation:

Canonical primary lock branches are:

- WP-01: `agent/wp-01-editor-template-management` in LoreItems;
- WP-02: `agent/wp-02-destructive-administration` in LoreItems;
- WP-03: `agent/wp-03-mass-distributions` in LoreItems;
- WP-04: `agent/wp-04-production-hardening` in LoreItems;
- WP-05: `agent/wp-05-live-acceptance-release` in LoreItems;
- WP-06: `agent/wp-06-loreitems-integration` in EnthusiaTags.

WP-06 also requires inspection of its contract-defined LoreItems branches `docs/wp-06-complete` and `agent/wp-06-loreitems-api-blocker`.

1. Refresh live `main`, recent merges, all open and draft PRs, requested changes, unresolved review threads, exact-head checks, workflow runs, commit statuses, and every canonical package branch named in the six package contracts. For WP-06, also inspect its package-defined Tags branch and any package-defined LoreItems finalization or API-blocker branch.
2. Read `REQUIREMENTS.md`, `docs/architecture.md`, `docs/implementation-plan.md`, this file, `WORKSPACE-STATE.md`, `WORK-QUEUE.md`, all six package contracts, and `reports/agent-handoffs/latest.md` from live `main`.
3. Classify a package branch or PR as unfinished when an open or draft canonical PR exists, or when the canonical branch head is not contained in verified live `main` and the package is not `COMPLETE`. A branch whose head is already contained in `main` through a verified merge is historical, not active.
4. If live GitHub shows unfinished work for more than one package, duplicate competing PRs, or otherwise conflicting canonical locks, stop without changing implementation and publish a clear inconsistency report listing every branch, PR, head SHA, recorded status, and conflict.
5. If exactly one unfinished package exists, resume it before considering any other package. Branch or PR presence outranks stale `READY`, `BLOCKED`, or handoff text. Recorded `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING` packages receive resume priority.
6. When no unfinished package exists, select the lowest-numbered `READY` package whose exact dependencies are verified complete on live GitHub.
7. Never select `BLOCKED` or `COMPLETE`. Never select a later package because it appears easier. Never begin more than one package.

## Claim and concurrency protocol

The exact package branch and exact package PR are the concurrency lock.

- When selecting a `READY` package, create the exact branch from the refreshed live `main` SHA using an atomic create-ref operation. Never create an alternate branch name.
- If branch creation reports that the canonical ref already exists, re-fetch it and resume it. Do not create a duplicate.
- Create or resume the exact draft PR title from the package contract. If another worker already created the canonical branch or PR, use it rather than opening another PR.
- Immediately make a GitHub-backed checkpoint commit on that canonical branch setting the package to `IN_PROGRESS` and recording the claim evidence.
- Use only fast-forward, expected-head, or compare-and-swap updates. Never force-push a package branch.
- Re-fetch live GitHub immediately after claiming. Continue only if the branch, PR, and exact head agree with the claim.
- If the canonical head changes between reconciliation and claim, a push is rejected because the head moved, or another checkpoint lands during the claim sequence, treat that as concurrent activity. Reconcile once and stop with a conflict report rather than allowing two workers to continue simultaneously.

A stable existing unfinished branch may be resumed by committing a new fast-forward resume checkpoint based on its exact observed head. If that head moves during the resume attempt, the worker stops as a concurrent claimant.

## Durable checkpoint protocol

After every major coherent section and always before an intentional stop, the worker commits a checkpoint to the canonical package branch. The committed record must contain:

- active package;
- status;
- branch;
- PR;
- exact implementation/evidence head SHA being checkpointed;
- completed acceptance criteria;
- remaining acceptance criteria;
- tests run and exact results;
- known findings;
- blocker, when applicable;
- exact next action.

The checkpoint metadata commit normally names the immediately preceding implementation or evidence commit because a commit cannot contain its own not-yet-created SHA. The PR may additionally identify the resulting checkpoint commit SHA after push. A local report, downloadable file, uncommitted note, or chat response is not a checkpoint.

For WP-01 through WP-05, checkpoint state belongs in the committed LoreItems workflow/handoff files on the package branch. For WP-06, the same fields must be committed in the canonical Tags PR branch's durable implementation or handoff documentation, with final program state mirrored through the package-mandated LoreItems finalization PR.

## Interruption and blocker protocol

- A worker that cannot finish and has no verified external blocker marks the package `PARTIAL` in a final checkpoint.
- All completed code, tests, findings, and evidence remain on the same canonical branch and PR.
- The worker does not create a follow-up package, smaller substitute, or alternate branch.
- The next worker resumes that same branch and the remaining acceptance checklist before selecting anything else.
- If an abrupt timeout prevents a final `PARTIAL` checkpoint, the existing canonical branch or PR still identifies the package to resume and outranks stale `READY` text.
- `BLOCKED` is used only when a specific external dependency is verified to prevent further progress; the checkpoint must identify that dependency and the evidence required to unblock it.

## Evidence and merge policy

- All evidence identifies the exact tested head SHA.
- Any commit after a passing run makes that run stale.
- Codacy is verified through the repository's exact-head CI step and any applicable GitHub status/check.
- Unsupported claims are blockers to completion, not harmless wording problems.
- Review threads are inspected directly; a lack of submitted reviews does not prove zero unresolved threads.
- Merge only through a normal merge commit. Never squash or rebase a package PR.

## Completion and advancement protocol

When every acceptance criterion, test, review, and exact-head gate is satisfied, the final package-branch commit prepares the complete transition in the same PR:

- set the current package to `COMPLETE`;
- unlock only the exact next package as `READY`, when one exists;
- leave every later package `BLOCKED`;
- update completed/remaining counts and weighted progress;
- record final exact-head evidence and the required next action.

These branch-local final-state changes are prospective and receive no global credit while the PR is open. They become authoritative only when that exact commit is normally merged and live `main` plus every package-specific post-merge or release gate is verified. If merge or post-merge verification fails, the package remains `VERIFYING` and the next package remains unavailable in authoritative live state.

After successful normal merge and live verification, the worker records any package-required GitHub evidence, confirms the authoritative state, and stops. It does not create, claim, implement, or inspect implementation for the newly unlocked package beyond confirming its status.

## Routing and loophole audit

The workflow closes these failure modes as follows:

- Two workers racing to claim: atomic canonical branch creation, expected-head checkpointing, immediate re-fetch, and stop-on-head-movement prevent parallel continuation.
- Interrupted worker: `PARTIAL` plus committed checklist and exact next action preserves resumable state; an abrupt timeout still leaves the canonical lock discoverable.
- Stale `READY` with active branch: live branch/PR presence outranks queue snapshots.
- Skipping `PARTIAL`: unfinished-package resumption occurs before new selection, with explicit priority for resumable statuses.
- Selecting a blocked later package: selection is limited to the lowest-numbered dependency-verified `READY` package.
- Claiming partial work as complete: only the full acceptance, review, exact-head, normal-merge, and live-verification sequence permits `COMPLETE`.
- Premature weighted progress: only authoritative live-`main` `COMPLETE` statuses count.
- Local-only handoffs: checkpoints must be committed to GitHub on the canonical branch.
- Ad hoc subpackages: alternate package identities, branches, or substitute scopes are prohibited.
- Beginning the next package after merge: the completing worker must stop after verification.
