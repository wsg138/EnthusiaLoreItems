# Enthusia Sentinel worker operating policy

This file is mandatory reading for EnthusiaLoreItems package workers whenever live Paper/Leaf validation, restart validation, staging evidence, or Sentinel-backed acceptance is relevant.

The repository is already onboarded to the existing production Enthusia Sentinel service controlled by `wsg138/EnthusiaStaff-Staging`. Do not create another Sentinel instance, another GitHub App, or a PAT-based substitute.

## Authority and discovery

Before using Sentinel, read from the current live branches rather than relying on memory:

1. this file from current LoreItems `main`;
2. `.enthusia-test.yml` from the exact LoreItems head being tested;
3. `docs/sentinel-staging.md` from current LoreItems `main`;
4. the current `wsg138/EnthusiaStaff-Staging/docs/sentinel-commands.md`, `docs/repository-onboarding.md`, and any Sentinel policy/operating documentation they reference.

Live Sentinel policy and live repository manifests outrank old handoffs, chat text, and historical command examples.

The current LoreItems manifest intentionally enables only the profiles declared in `.enthusia-test.yml`. At onboarding those are `startup` and `restart`. Never assume another profile exists, is authorized, or is appropriate merely because Sentinel supports it for another repository.

## Existing production integration

LoreItems is authorized under immutable repository ID `1320758587` and canonical name `wsg138/EnthusiaLoreItems` in trusted Sentinel policy.

The existing `Enthusia Sentinel` GitHub App installation repository selector is an owner-controlled account setting. The owner may use either `Only select repositories` or `All repositories`. Workers must respect the owner's current choice and must not automatically broaden or narrow it.

Sentinel execution authorization is independent of App installation visibility. A repository must be enabled by immutable numeric ID/canonical name in trusted Sentinel policy, and production Sentinel must use short-lived installation tokens scoped to the repositories currently enabled by that policy. App visibility alone never authorizes execution.

Workers must preserve these boundaries:

- use the existing `Enthusia Sentinel` GitHub App;
- respect the owner-selected App installation mode (`selected` or `all`); do not revert an owner-approved `All repositories` setting merely because an older handoff expected selected mode;
- never create a replacement App or PAT;
- never treat App access alone as Sentinel authorization;
- never manually edit the installed Sentinel policy as a substitute for the canonical Sentinel Foundation deployment;
- never weaken Sentinel authorization, policy-scoped token, resource, cleanup, isolation, or exact-SHA checks merely to make a test pass.

## Exact-head artifact contract

Sentinel acceptance is exact-head evidence.

For any LoreItems PR head submitted to Sentinel:

- the PR must be open, same-repository, and non-draft when the command contract requires it;
- the exact current head SHA must have a successful GitHub Actions run that produced the dedicated artifact named `enthusialoreitems-plugin`;
- the artifact must resolve to exactly the manifest-declared JAR path `build/libs/EnthusiaLoreItems.jar`;
- Sentinel must resolve the artifact from the exact requested SHA rather than branch-latest, a release download, an arbitrary URL, or a local file;
- any commit after the tested SHA makes the old Sentinel result stale for the new head, including documentation and workflow-only commits.

Do not rebuild an unchanged exact head merely for ceremony when its successful, unexpired exact-SHA artifact remains valid and resolvable.

## Command path

Production acceptance must use Sentinel's real GitHub command path.

When `restart` is the required supported profile, post one exact PR conversation comment:

```text
@enthusia-sentinel test restart
```

The comment body must contain exactly the command expected by the current Sentinel command contract. For any other supported profile, obtain the exact current syntax from live Sentinel documentation before posting it.

Do not manually enqueue jobs, write directly to Sentinel's queue, or replace the production GitHub-App path with a local fixture.

A valid production chain is:

GitHub PR/comment -> existing Enthusia Sentinel App -> policy-scoped short-lived installation token -> outbound Sentinel polling -> repository/requester authorization -> exact PR head -> exact manifest -> exact successful artifact -> durable queue -> rootless Paper execution -> cleanup -> GitHub result.

## Result semantics

Only the terminal result code required by the applicable profile counts as a pass. For the current LoreItems restart contract, success is `PAPER_RESTART_OK`.

`PAPER_RESTART_OK` means Sentinel completed the full restart contract, including:

- exact-SHA manifest/artifact binding;
- Paper cycle 1 reaching readiness;
- clean shutdown and complete process reap;
- reuse of the same disposable state for cycle 2;
- Paper cycle 2 reaching readiness and satisfying restart/persistence verification;
- second clean shutdown/reap;
- disposable runtime cleanup;
- terminal GitHub reporting.

Queued, rejected, cancelled, stale-SHA, missing-artifact, cleanup-failed, resource-gated, or otherwise failed jobs are not passes.

## Resource and infrastructure failures

Sentinel's resource gates are safety controls, not optional test thresholds.

If a job is rejected or stopped because of temperature, memory, disk, client-count, queue, or another trusted host-resource gate:

- classify it as an environmental/resource result unless evidence proves a product defect;
- do not weaken or raise Sentinel's safety limits to force a pass;
- do not repeatedly hammer an unchanged unavailable condition;
- retry only after the external condition materially changes and the previous relevant job is terminal;
- preserve failed-attempt evidence when it materially explains the acceptance history.

If Sentinel itself has a confirmed defect, fix Sentinel in `wsg138/EnthusiaStaff-Staging` through its normal reviewed PR/deployment path. Do not work around a proven control-plane defect inside LoreItems.

## Failure ownership

A failed Sentinel run must be diagnosed before changing code. Classify the proven owner of the failure, for example:

- LoreItems manifest;
- LoreItems CI artifact path/name or exact-SHA publication;
- GitHub App installation/permission access;
- Sentinel trusted policy or policy-scoped token implementation;
- plugin startup/runtime dependency;
- plugin lifecycle/shutdown;
- restart/persistence behavior;
- cleanup/process residue;
- host resource gate;
- actual Sentinel implementation defect.

Fix the component that owns the proven problem. Rerun only after a material correction or a materially changed external condition.

## Required durable evidence

For every Sentinel result used as package or release evidence, record on GitHub at least:

- repository and PR number;
- repository ID when relevant;
- exact tested head SHA;
- exact command comment ID/link;
- Sentinel response comment/check ID/link;
- Sentinel job ID;
- exact successful workflow run for the tested SHA, including workflow run ID;
- exact `enthusialoreitems-plugin` artifact for that workflow run, including artifact ID and the manifest-declared JAR path `build/libs/EnthusiaLoreItems.jar`;
- terminal result code;
- cycle/lifecycle evidence required by the selected profile;
- cleanup state, including active queue and runtime residue when relevant;
- any failed prior attempt that materially explains the final successful retry.

If Sentinel's own response omits workflow or artifact identity, resolve those values directly from GitHub Actions before accepting the Sentinel result as package or release evidence. If the exact successful workflow run, artifact name, artifact ID, or JAR path cannot be resolved for the tested SHA, the Sentinel result is incomplete evidence and must not be counted as a package/release PASS.

Never put credentials, private keys, installation tokens, or other secrets into package evidence.

A chat statement, local log, local artifact, or unsupported inference is not Sentinel evidence.

## WP-05 use

WP-05 remains the fixed live-acceptance/release package. Sentinel is the canonical production path for the LoreItems profiles it actually supports, especially startup/restart lifecycle validation. Sentinel does not automatically replace the rest of the WP-05 acceptance matrix.

Workers must not mark an unrelated WP-05 case PASS merely because `startup` or `restart` passed. Every WP-05 case still requires the evidence defined by the WP-05 contract and current acceptance harness.

When a supported WP-05 lifecycle case can be proven through Sentinel, prefer the real production Sentinel path over an ad hoc local substitute and record the exact result in the WP-05 evidence ledger.

## Long-lived branch reconciliation

The canonical WP-05 branch predates Sentinel onboarding. More generally, any long-lived package branch may contain stale copies of workflow policy or may lack files later added to `main`.

Current live `main` is authoritative for orchestration and Sentinel policy. Before merging a long-lived package branch:

- deliberately reconcile current `main` into the package branch using repository-approved non-destructive history handling;
- never rebase or force-push merely to obtain the new policy;
- preserve `.enthusia-test.yml`, the `enthusialoreitems-plugin` CI publication, `docs/sentinel-staging.md`, this operating-policy file, and newer workflow rules from live `main`;
- inspect conflicts rather than accepting the package branch's older orchestration files;
- do not let a package merge silently remove or revert Sentinel onboarding.

A worker may merge current `main` into the package branch when technically necessary and allowed by the package workflow, but must not use routine base synchronization as a reason to overwrite unrelated active work.

## Worker stopping rule

Sentinel does not change the fixed-package routing rules. A worker still works on only the automatically selected package, publishes durable partial state when unfinished, and stops according to the universal worker protocol.

If Sentinel is waiting on a real external condition and other in-package work remains actionable, continue that safe in-package work. Use `BLOCKED` only when the package's next required work is genuinely prevented by the verified external condition and no other safe actionable work remains.
