# Universal ChatGPT start instructions

This is the permanent entrypoint for ChatGPT development chats working on `wsg138/EnthusiaLoreItems`.

The user should only need to send:

> Use the GitHub connector to open `wsg138/EnthusiaLoreItems`, read `CHATGPT_START_HERE.md`, read the current handoff, and continue the next unfinished safe step. Follow the repository documents as binding requirements. Do not ask me to restate the project.

## Mission

Build and harden EnthusiaLoreItems as a production Paper/Leaf 1.21.11 plugin for the single Enthusia SMP. It must create, serialize, protect, distribute, edit, locate, reconcile, and safely remove tracked lore items while surviving restarts and avoiding item loss, duplicate delivery, main-thread blocking, force-loaded chunks, and unbounded work.

The authoritative product and engineering requirements remain in:

1. `REQUIREMENTS.md`
2. `docs/architecture.md`
3. `docs/implementation-plan.md`

Use `wsg138/EnthusiaStaff` only as a reference for strong hexagonal boundaries, lifecycle safety, durable workflows, architecture testing, and documentation. Do not copy its proxy, multi-server, MariaDB, website, or moderation complexity.

Reference access for future repository agents:

- Prefer `wsg138/EnthusiaStaff-Staging` as the durable GitHub source for inspectable EnthusiaStaff reference code and workflow patterns.
- Historical verified-runtime evidence is available from EnthusiaStaff Actions run `30794945133`, artifact `8848768264`: `https://github.com/wsg138/EnthusiaStaff/actions/runs/30794945133/artifacts/8848768264`.
- GitHub Actions artifacts may expire; the staging repository is the durable reference and the artifact is evidence for that specific run only.

Do not modify `wsg138/EnthusiaTags` until all six LoreItems phases are merged and the implementation plan reaches the separate Tags integration phase.

## Handoff-first startup

Do not begin every chat by re-reading the entire repository.

### 1. Resolve the small amount of live state that can change

- Resolve the current `main` head.
- List open pull requests.
- Identify the active implementation/review PR, if one exists.
- Read that PR's draft state, head branch, head SHA, description, checks, and unresolved review comments.

This live state is authoritative. A handoff report may be stale after later commits.

### 2. Read the current handoff from the correct branch

- When an active PR exists, read `handoffs/CURRENT.md` from that PR's head branch.
- When no active PR exists, read `handoffs/CURRENT.md` from `main`.
- Then read the latest numbered report linked by `CURRENT.md`.
- Read only the earlier reports listed under `Required prior reports`.
- Read `handoffs/README.md` when creating or updating handoffs.

The handoff should identify the phase, work already completed, relevant files, evidence, unresolved risks, and exact next step.

### 3. Inspect only what is relevant to the next step

Use the handoff and active PR changed-file list to select the code, tests, migrations, and document sections needed for the immediate task.

Broaden to a full phase/repository review only when:

- no usable handoff exists;
- live GitHub state contradicts the handoff;
- a new implementation phase is starting;
- a security, duplication, item-loss, data-corruption, or architecture risk is suspected;
- an active PR appears complete and needs production-readiness review;
- a merge changed assumptions or introduced conflicts;
- the binding documents must be changed.

Handoffs reduce repeated work; they do not authorize blind trust. Verify every claim that materially affects a write.

## Determine the next action

Follow this order.

### Planning is not merged

Work only on the planning PR. Do not add production code against unresolved requirements. Keep planning changes documentation-only and do not merge without explicit user authorization.

### An active PR exists for the current phase

Continue that PR and branch. Do not create a competing PR.

- Read the current handoff, PR description, checks, and review comments.
- Fix in-scope defects and complete the exact next step.
- Preserve the phase boundary.
- Update tests, PR evidence, and handoffs.
- Keep the PR draft until implementation and evidence are complete.
- Do not merge without explicit user authorization.

### No active PR exists for the first incomplete phase

Confirm the prior phase is merged into `main`, then create a feature branch and draft PR for exactly the first incomplete phase in `docs/implementation-plan.md`.

Suggested branch format:

```text
agent/loreitems-pr<N>-<short-scope>
```

At a new phase boundary, read the full scope for that phase plus the relevant requirements and architecture sections before implementing it.

### The active PR appears complete

Perform a production-oriented review covering at least:

- item loss, accidental deletion, duplicate creation, and duplicate delivery;
- restart, reload, shutdown, stale-worker, and compare-and-set races;
- transaction, migration, idempotency, lease/claim, and recovery failures;
- Bukkit access off the owning thread;
- database or filesystem I/O on the server thread;
- force-loaded chunks or whole-world scans;
- unbounded executors, queues, caches, retries, scans, repository results, GUI loads, or shutdown drains;
- retained mutable Bukkit objects across async boundaries;
- offline, unknown, renamed, Java, and Floodgate `*` players;
- nested shulker/bundle paths and unloaded-location claims;
- unsafe destructive confirmation or deletion recovery;
- command, GUI, and application-service authorization;
- misleading test, CI, Codacy, CodeRabbit, or live-server claims;
- dependency cycles, platform imports in core code, and god classes.

Fix verified defects within the current phase and add focused regression tests where practical. If evidence is missing, leave the PR draft and state what remains.

### All six LoreItems phases are merged

Begin the separate EnthusiaTags integration only then. The integration must use the versioned Bukkit service API, not commands or reflection, and must use the Tags reward claim identity as the external idempotency key. An unavailable LoreItems service must not result in a claimed reward.

## Non-negotiable engineering rules

- Java 21 targeting Paper/Leaf 1.21.11.
- One SMP server; no proxy or cross-server coordination.
- SQLite WAL persistence behind ports.
- Domain/application code must not import Bukkit, JDBC, YAML, GUI, or filesystem implementations.
- Bukkit/Paper objects may be read or mutated only on the correct server/entity thread.
- Database and filesystem I/O must not run on the server thread.
- Never force-load chunks for tracking, editing, delivery, reconciliation, or deletion.
- Never retain live `Player`, `Inventory`, `Entity`, `Chunk`, or mutable `ItemStack` references across async boundaries.
- Persist durable intent before inventory creation, replacement, or destruction.
- Use idempotency keys, unique constraints, revisions, claim tokens/fencing, and compare-and-set transitions.
- Ambiguous state becomes an anomaly or operator recovery workflow. Never guess by deleting one duplicate.
- Duplicate instance UUIDs remain usable but flagged; online staff and console are warned every five minutes until resolved.
- Internal definition IDs and instance UUIDs remain in PDC/database only, never visible to ordinary players.
- Every tracked item is unstackable. Preserve and flag malformed stacks rather than silently multiplying or destroying them.
- Full inventories never cause lore items to drop.
- Offline direct delivery and mass campaigns persist indefinitely across restarts.
- Cancelling a distribution stops pending recipients and keeps delivered items.
- Deleted definitions vanish from normal GUIs/tab completion, while a minimal hidden deleted-ID marker remains for stale restored copies.
- Repository reads, workers, queues, batches, retries, caches, reconciliations, GUI pages, per-tick work, and shutdown drains must be explicitly bounded.
- Optional integrations fail independently without corrupting authoritative state.
- Avoid reflection and console-command dispatch as integration mechanisms.
- Avoid broad static-analysis suppressions; document narrow genuine false positives.
- Never claim a check passed without direct evidence.

## Git and PR workflow

- Never commit directly to `main`.
- Reuse the active phase branch.
- Keep implementation PRs as drafts until ready for review.
- Use focused commits and avoid unrelated cleanup or later-phase features.
- Inspect the complete relevant diff before finishing.
- Run the strongest available checks and record exact results.
- Inspect GitHub Actions, Codacy, CodeRabbit, and review threads after pushing.
- Do not merge without explicit user authorization in the current chat.

The active PR body must remain accurate about scope, deferred work, architecture, migrations/state machines, thread ownership, queue bounds, tests, visible automation, limitations, and required live-server validation.

## Required end-of-session handoff

A chat that changes code, documentation, PR state, or the next-step decision must complete the procedure in `handoffs/README.md` before ending:

1. Create a new immutable numbered report in `handoffs/`.
2. Update `handoffs/CURRENT.md` to point to it and state the exact next step.
3. Append it to `handoffs/INDEX.md`.
4. Update the active PR body when its status or evidence materially changed.
5. Commit these changes to the same active branch.

The final response must include:

- phase and action taken;
- branch and PR;
- live head SHA;
- important changes;
- tests/checks actually run;
- automation actually observed;
- unresolved risks;
- exact next step;
- new handoff report path.

Do not merely propose work when the tools allow in-scope progress. Use the handoff to resume efficiently, verify the necessary live state, perform the next safe task, and leave the next chat a better handoff.
