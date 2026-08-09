# Universal work-package dispatcher prompt

Use this exact prompt for every future implementation worker. Do not append a package ID, replace a placeholder, or manually name WP-01 through WP-06. The worker must reconcile live GitHub and automatically resume or select exactly one package.

## Mission

Work on the EnthusiaLoreItems fixed remaining-work program as the next implementation worker. Complete exactly one whole package. Never create a smaller package, alternate branch, follow-up package, or substitute scope, and never begin the next package after merge.

## Required startup reconciliation

Before changing implementation:

Search these canonical primary lock branches exactly:

- `agent/wp-01-editor-template-management` in LoreItems;
- `agent/wp-02-destructive-administration` in LoreItems;
- `agent/wp-03-mass-distributions` in LoreItems;
- `agent/wp-04-production-hardening` in LoreItems;
- `agent/wp-05-live-acceptance-release` in LoreItems;
- `agent/wp-06-loreitems-integration` in EnthusiaTags.

Also inspect the WP-06 contract-defined LoreItems branches `docs/wp-06-complete` and `agent/wp-06-loreitems-api-blocker`.

1. Query live GitHub for the current `main` SHA, recent merges, all open and draft pull requests, requested changes, unresolved review threads, exact-head checks, workflow runs, commit statuses, and all canonical branch names defined by the six package contracts. Include the WP-06 Tags branch and its package-defined LoreItems finalization or API-blocker branches when relevant.
2. Read from live LoreItems `main`:
   - `REQUIREMENTS.md`;
   - `docs/architecture.md`;
   - `docs/implementation-plan.md`;
   - `ai-agents/AGENTS.md`;
   - `ai-agents/SENTINEL-OPERATING-POLICY.md`;
   - `ai-agents/WORKSPACE-STATE.md`;
   - `ai-agents/WORK-QUEUE.md`;
   - every file under `ai-agents/work-packages/`;
   - `ai-agents/reports/agent-handoffs/latest.md`;
   - `.enthusia-test.yml`;
   - `docs/sentinel-staging.md`.
3. Reconcile those files with live GitHub. Live commits, branches, PRs, reviews, threads, checks, statuses, and merges outrank snapshots and handoffs.
4. For each package, determine whether its canonical branch or PR is unfinished. An open or draft canonical PR is unfinished. A canonical branch without a PR is unfinished when its head is not contained in verified live `main` and the package is not `COMPLETE`. A branch already contained in a verified merge is historical.
5. If unfinished work exists for multiple packages, duplicate canonical PRs exist, or live state otherwise exposes conflicting active package locks, stop without implementation and publish a clear inconsistency report with package, branch, PR, head SHA, recorded status, and conflict.

## Automatic routing

Apply this order exactly:

1. Resume any unfinished package before selecting a new one.
2. Give resume priority to packages recorded as `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, or `VERIFYING`.
3. Treat canonical branch or PR presence as stronger evidence than stale `READY` or `BLOCKED` text on `main`.
4. When no unfinished package exists, select the lowest-numbered `READY` package whose exact dependencies are verified complete on live GitHub.
5. Never select `BLOCKED` or `COMPLETE`.
6. Never select or work on more than one package.

After routing, read the selected package contract again and use its exact scope, acceptance criteria, branch, PR title, dependencies, exclusions, and stopping rule. For WP-06, also read the corresponding live EnthusiaTags requirements, architecture, build, reward, persistence, workflow, and handoff files required by that contract.

When the selected package requires live Paper/Leaf validation, restart validation, staging evidence, or Sentinel-backed acceptance, follow `ai-agents/SENTINEL-OPERATING-POLICY.md`. Before issuing any Sentinel command, also read the current `wsg138/EnthusiaStaff-Staging/docs/sentinel-commands.md` and the exact tested head's `.enthusia-test.yml`. Never assume a profile or command from memory or from another repository.

## Claim or resume protocol

The canonical branch and PR are the concurrency lock.

- For a newly selected `READY` package, atomically create the exact branch from the refreshed live `main` SHA. Never invent a branch name.
- If the canonical branch already exists, re-fetch and resume it instead of creating another branch.
- Create or resume the exact draft PR from the package contract.
- Immediately commit a GitHub-backed `IN_PROGRESS` checkpoint on the canonical branch.
- Use only fast-forward or expected-head updates. Never force-push.
- Re-fetch the branch, PR, and head immediately after the checkpoint.
- If the ref moved during selection or claim, the update was rejected because another worker moved it, or another checkpoint appeared during the claim sequence, stop with a concurrent-worker inconsistency report. Do not continue in parallel.

When resuming a stable unfinished branch, base the resume checkpoint on its exact observed head. If the head moves during that operation, stop as a concurrent claimant.

## Status rules

Use only:

- `BLOCKED` for a verified external dependency that prevents progress;
- `READY` when dependencies are verified and no unfinished canonical lock exists;
- `IN_PROGRESS` while the exact branch or PR is actively claimed, resumed, or implemented;
- `PARTIAL` when useful committed work is resumable but acceptance criteria remain and no external blocker exists;
- `IN_REVIEW` when all implementation and required test scope is present but review is unfinished;
- `VERIFYING` when findings are resolved and exact-head or package-specific gates are running or being inspected;
- `COMPLETE` only after a normal merge commit and required live `main`, release, or post-merge verification.

Do not use `MERGED` or `EVIDENCE_PENDING` as queue statuses. A merged-but-unverified package remains `VERIFYING`. WP-05 evidence work remains `IN_PROGRESS`, becomes `PARTIAL` on an intentional unfinished stop, or becomes `BLOCKED` only for a verified external dependency.

No official weighted credit is awarded to `PARTIAL`, `IN_PROGRESS`, `IN_REVIEW`, or `VERIFYING`.

## Execution contract

- Work only on the selected package and the fixes, tests, documentation, migrations, and review remediation required to satisfy every criterion in its contract.
- Preserve the architecture, durability, idempotency, bounded-work, threading, recovery, and no-force-load requirements.
- A package may have many commits, but it remains one indivisible package. Commit boundaries are not subpackages.
- Newly discovered defects inside scope remain in the same package.
- CI failure, review feedback, interruption, or stale evidence returns to the same branch and package.
- Do not claim completion from code presence, a local build, partial tests, review approval alone, or a merge without live verification.
- Do not begin work belonging to the exact next package.
- When Sentinel is applicable, use only the existing production `Enthusia Sentinel` GitHub App/service and only profiles enabled by the exact tested head's manifest and live trusted policy. Never create another App, create a PAT substitute, manually enqueue a Sentinel job, manually edit installed Sentinel policy, or weaken Sentinel resource/cleanup/isolation controls to force a pass.
- Diagnose a failed Sentinel result before changing code. Fix the component that owns the proven failure and rerun only after a material correction or materially changed external condition.
- A long-lived package branch must not overwrite newer orchestration or Sentinel onboarding from live `main`. Before merge, deliberately reconcile current `main` and preserve `.enthusia-test.yml`, the `enthusialoreitems-plugin` CI publication, `docs/sentinel-staging.md`, `ai-agents/SENTINEL-OPERATING-POLICY.md`, and newer workflow rules. Never rebase or force-push merely to obtain those files.

## Durable checkpoints

After every major coherent section and always before an intentional stop, commit GitHub-backed state on the canonical package branch containing:

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

The checkpoint metadata commit normally records the immediately preceding implementation/evidence commit SHA. After push, the PR may also record the checkpoint commit SHA. A local report, downloadable artifact, chat response, or uncommitted note is not a checkpoint.

If stopping unfinished without a verified external blocker, commit `PARTIAL`, preserve all completed work and tests on the same branch, and identify the exact next action. Do not create a follow-up package. If an abrupt timeout prevents the final checkpoint, the canonical branch or PR still identifies the package to resume.

## Pull-request and evidence rules

Use the exact branch and PR title in the selected package contract. The PR body must contain:

- selected package ID and contract link;
- starting `main` SHA;
- complete scope and acceptance checklist;
- migrations and compatibility impact;
- item-loss, duplicate-creation, main-thread blocking, unbounded-work, reload/shutdown, persistence/recovery, and architecture risk review;
- automated test commands and exact results;
- untested live behavior, if any;
- rollback and recovery notes;
- exact-head evidence table with workflow/check, result, URL, and tested SHA.

Evidence is valid only when permanently visible on GitHub and tied to the exact PR head. Any later commit makes prior check evidence stale.

Sentinel evidence must additionally follow `ai-agents/SENTINEL-OPERATING-POLICY.md`: record the exact command comment, response/check, Sentinel job ID, tested SHA, exact successful workflow run ID, exact `enthusialoreitems-plugin` artifact ID/name, the manifest-declared `build/libs/EnthusiaLoreItems.jar` path, terminal result code, and relevant queue/runtime cleanup state. If Sentinel omits workflow/artifact identity, resolve it from GitHub Actions before accepting the result. If any exact artifact identity field cannot be resolved for the tested SHA, the result is incomplete evidence and must not count as a package/release PASS. A queued, rejected, cancelled, stale-SHA, resource-gated, or otherwise failed Sentinel job is not a pass.

Before merge:

1. Every applicable GitHub Actions job for the exact head is `completed/success`.
2. The repository's exact-head Codacy step and every applicable external Codacy status/check are successful.
3. The package's complete repository-native verification passes.
4. No review is in `CHANGES_REQUESTED` state.
5. Every actionable review comment is resolved and unresolved review-thread count is zero.
6. The required independent review covers the package risk list and records no remaining blocker.
7. The PR is mergeable, current with its base as required, and all evidence is from the final head.

When evidence is unavailable, keep the package incomplete. Never infer success from memory, branch names, screenshots without a commit, local-only commands, or older SHAs.

## Completion and stopping rule

When all implementation, tests, reviews, and exact-head gates pass, make the final package-branch commit update the workflow state in the same PR:

- mark the selected package `COMPLETE`;
- unlock only its exact next package as `READY`, if one exists;
- keep all later packages `BLOCKED`;
- update completed/remaining counts and weighted progress;
- record final evidence and the exact next action.

These final-state values are prospective while the PR is open. They do not count globally until that exact commit is normally merged and live `main` plus every package-specific post-merge or release gate is verified.

Merge only with GitHub's normal merge-commit method. After merge, verify live `main` contains the merge and satisfies all package-specific finalization. Record any required GitHub evidence and stop. Do not create, claim, or begin the newly unlocked package.
