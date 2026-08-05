# Agent workflow rules

## Authority order

Agents resolve conflicts in this order:

1. live GitHub state for the repository being changed;
2. the assigned work-package contract;
3. `WORK-QUEUE.md` and this file;
4. `REQUIREMENTS.md`;
5. `docs/architecture.md`;
6. `docs/implementation-plan.md`;
7. `WORKSPACE-STATE.md` and `reports/agent-handoffs/latest.md`;
8. older handoffs, chat messages, local notes, and assumptions.

A snapshot can be stale. A live merged commit, open PR, review, or exact-head check cannot be overridden by an older handoff.

## Fixed-package invariant

The remaining program contains exactly six packages: WP-01 through WP-06. Their identities, order, weights, required scope, and acceptance criteria are fixed by the committed package files.

- One implementation worker receives one package ID.
- The worker owns the entire package through review, merge, and required post-merge verification.
- A worker cannot choose a convenient subset.
- No `WP-01a`, `WP-01b`, `phase 1`, follow-up package, cleanup package, or equivalent subdivision may be created.
- Internal commits, checklists, or implementation sequences do not change the package boundary.
- Newly discovered defects inside the assigned scope are fixed in the same package.
- Review findings and CI failures return to the same branch and package.
- Scope changes require an explicit owner-approved amendment to the fixed package contract in a dedicated planning PR; an implementation worker cannot make that amendment in its implementation PR.

## Roles

### Planning/workflow agent

Maintains only the fixed workflow documents, reconciles stale state, and audits loopholes. It does not implement package functionality.

### Implementation worker

Implements exactly one assigned package, supplies all tests and documentation listed in the package, addresses review, merges normally, verifies `main`, and stops.

### Independent reviewer

Reviews the complete package against its contract rather than reviewing only the diff's apparent intent. The reviewer explicitly examines item loss, duplicate creation, destructive ambiguity, main-thread work, unbounded work, reload/shutdown, persistence/recovery, architecture boundaries, and evidence accuracy.

### Live-acceptance operator

Runs the WP-05 matrix on the designated Paper/Leaf server and supplies reproducible GitHub-backed evidence. The operator does not mark failures as accepted; confirmed defects remain in WP-05 until fixed and retested.

## Status model

Common package statuses are:

`BLOCKED -> READY -> IN_PROGRESS -> IN_REVIEW -> VERIFYING -> MERGED -> COMPLETE`

Permitted regressions are:

- `IN_REVIEW -> IN_PROGRESS` for review findings;
- `VERIFYING -> IN_PROGRESS` for failed or stale checks;
- any non-complete status -> `BLOCKED` for a verified external dependency;
- `BLOCKED -> READY` only after the dependency is verified complete on live GitHub.

WP-05 may use `EVIDENCE_PENDING` between `IN_PROGRESS` and `IN_REVIEW`. No package becomes `COMPLETE` until its normal merge commit and required post-merge verification are confirmed on live GitHub.

## Stale-state protocol

At every start and before every merge:

- refresh `main`, open/draft PRs, branches, recent merges, reviews, threads, checks, statuses, and workflow runs;
- match every evidence item to the current head SHA;
- resume a relevant unfinished PR;
- treat a handoff that names an already merged PR as stale;
- never infer that a branch is active merely because it exists;
- never infer that a feature is complete merely because foundation classes or tests exist.

When live state conflicts with a committed snapshot, the worker records the correction in the current package PR but does not rewrite fixed future package scope.

## GitHub-only durability

Durable program state consists of merged repository files, PR bodies and comments, reviews, check runs, workflow runs, commit statuses, releases, tags, and issues. Local reports and chat summaries are disposable. They may help prepare work but are not evidence or handoff state.

## Evidence and merge policy

- All evidence must identify the exact tested head SHA.
- Any commit after a passing run makes that run stale.
- Codacy must be verified through the repository's exact-head CI step and any applicable GitHub status/check.
- Unsupported claims are blockers, not harmless wording problems.
- Review threads must be inspected directly; a lack of submitted reviews does not prove zero unresolved threads.
- Merge only through a normal merge commit.
- After verification, the worker stops without starting the next package.
