# Universal ChatGPT start and handoff instructions

This file is the permanent entrypoint for new ChatGPT development chats working on `wsg138/EnthusiaLoreItems`.

The user should only need to send this message:

> Use the GitHub connector to open `wsg138/EnthusiaLoreItems`, read `CHATGPT_START_HERE.md`, determine the next unfinished safe step, and begin it. Follow the repository documents as binding requirements. Do not ask me to restate the project.

## Mission

Build and harden EnthusiaLoreItems as a production Paper/Leaf 1.21.11 plugin for the single Enthusia SMP. The plugin creates, serializes, protects, distributes, edits, locates, reconciles, and safely removes lore items while surviving restarts and avoiding item loss, duplicate delivery, main-thread blocking, force-loaded chunks, and unbounded work.

The final system must support:

- hidden definition IDs and per-copy instance UUIDs stored internally, never in player-visible text;
- one or many physical instances per lore-item definition;
- creating a definition from the held item and adopting additional held items into it;
- direct delivery to self, online players, offline players, and full-inventory queues;
- unstackable tracked items and safe anomaly handling if malformed stacks appear;
- duplicate-instance detection with staff/console warnings every five minutes until resolved;
- protection from despawn, fire, explosions, cactus and durability loss, while allowing deliberate void loss;
- Ender Chests, physical containers, item frames, glow item frames, armor stands, shulkers and bundles;
- last-confirmed tracking for unloaded locations without force-loading chunks;
- queued template revisions and removals across restarts;
- easy GUI navigation and chat-backed item editing;
- one-use mass-distribution files with durable delivery, unresolved future-player names, Floodgate `*` names, status, pause, resume, cancel, completion markers, and no removal of already delivered items when cancelled;
- an idempotent Bukkit service API for later EnthusiaTags rewards integration;
- low server overhead through event-driven observation, bounded workers, paging, batching, debouncing, backpressure, and explicit per-tick budgets.

## Binding documents

Before making any change, read these files in full from the current repository state:

1. `README.md`
2. `REQUIREMENTS.md`
3. `docs/architecture.md`
4. `docs/implementation-plan.md`
5. this file

Use `wsg138/EnthusiaStaff` only as a reference for strong hexagonal boundaries, lifecycle safety, durable workflows, architecture testing, and documentation. Do not copy its multi-server, proxy, MariaDB, website, or moderation complexity.

Use `wsg138/EnthusiaTags` only when the implementation plan reaches the separate Tags integration phase. Do not modify Tags early.

If repository code and the binding documents conflict, stop destructive work, explain the exact conflict, and favor the safer interpretation until the documents are corrected. Do not silently invent a new requirement.

## Mandatory startup inspection

Every new chat must inspect the actual GitHub state rather than assuming where the previous chat stopped:

1. Resolve the current head of `main` and inspect recent commits.
2. List open pull requests, including draft state, base/head branches, checks, review comments, and changed files.
3. Read any active PR description and unresolved review threads.
4. Inspect GitHub Actions or commit statuses that are actually available.
5. Compare merged code on `main` against every phase in `docs/implementation-plan.md`.
6. Determine the first phase that is not demonstrably merged and complete.
7. State the phase and immediate objective before editing.

A phase counts as complete only when its implementation is merged into `main`. A draft branch, passing local build, or open PR is not a completed phase.

## Decide what to do next

Follow this order.

### 1. Planning gate is still open

If the architecture/requirements planning PR is still open or not merged:

- work only on reviewing, correcting, or completing that planning PR;
- do not begin production plugin code against unresolved requirements;
- inspect it for missing loss, duplication, restart, concurrency, performance, Floodgate, nested-storage, editing, deletion, and distribution cases;
- keep it documentation-only;
- when sound, leave it ready for user review and merge, but do not merge without explicit user authorization.

### 2. An implementation PR for the current phase already exists

Do not create a competing PR.

- Continue on its existing branch when it is clearly unfinished.
- Read all review comments and CI failures before changing code.
- Fix in-scope defects, unsafe assumptions, architecture violations, test gaps, and static-analysis findings.
- Preserve the PR's phase boundary; do not pull later-phase features into it merely because they are convenient.
- Update its PR body with exact checks and remaining limitations.
- Keep the PR draft until its implementation and evidence are complete.
- Do not merge without explicit user authorization.

### 3. The current phase has no active PR

Create a new feature branch from the current `main` head and open a draft PR for exactly the first incomplete phase in `docs/implementation-plan.md`.

Suggested branch format:

```text
agent/loreitems-pr<N>-<short-scope>
```

Implement only that phase. Logical supporting work that is necessary for the phase is allowed; speculative future systems are not.

### 4. The active PR appears implementation-complete

Perform a production-oriented review before calling it ready:

- item loss or accidental deletion;
- duplicate creation or duplicate delivery;
- restart/reload/shutdown races;
- stale worker and compare-and-set failures;
- database transaction or migration errors;
- Bukkit access off the owning server thread;
- server-thread database/filesystem work;
- force-loaded chunks or world-wide scans;
- unbounded executors, queues, caches, retries, scans, result sets, or GUI loads;
- retained mutable Bukkit objects across async boundaries;
- incorrect behavior for offline, unknown, renamed, Java, or Floodgate `*` players;
- nested shulker/bundle paths and unloaded-container claims;
- unsafe destructive confirmation or deletion recovery;
- weak authorization at command, GUI, and application-service boundaries;
- misleading test, CI, Codacy, or live-server claims;
- cyclic dependencies, platform imports in the core, and god classes.

Fix verified defects within the same phase. Add tests that reproduce each fixed defect where practical. If completion cannot be proven, leave the PR draft and clearly identify the missing evidence.

### 5. All six LoreItems phases are merged

Only then begin the separate EnthusiaTags integration phase described in `docs/implementation-plan.md`.

The integration must:

- add a dedicated `LORE_ITEM` reward action;
- call the versioned Bukkit service API, not commands or reflection;
- use the Tags reward claim identity as the external idempotency key;
- leave the Tags reward unclaimed when LoreItems is unavailable or delivery was not durably accepted;
- recover safely across either plugin reloading and server restarts;
- place reward rebalancing in a clearly separated configuration change.

## Engineering rules

These apply to every phase and every fix:

- Java 21; target Paper/Leaf 1.21.11.
- Single SMP server; no proxy or cross-server design.
- SQLite in WAL mode behind persistence ports.
- Domain/application code must not import Bukkit, JDBC, YAML, GUI, or filesystem implementations.
- Bukkit/Paper objects may be read or mutated only on the correct server/entity thread.
- Database and filesystem I/O must not run on the server thread.
- Never force-load chunks for tracking, editing, delivery, reconciliation, or deletion.
- Never keep live `Player`, `Inventory`, `Entity`, `Chunk`, or mutable `ItemStack` references across async boundaries.
- Persist durable intent before inventory creation, replacement, or destruction.
- Use idempotency keys, unique constraints, claim tokens/fencing, revisions, and compare-and-set transitions.
- Ambiguous state becomes an anomaly or quarantine-style operator workflow; never guess by deleting one copy.
- Duplicate instance UUIDs remain usable but visibly flagged to staff. Warn online staff and console every five minutes until resolved.
- Internal IDs remain in PDC/database only and never appear in ordinary name, lore, tooltip, player messages, or player-facing commands.
- All tracked items are unstackable. A malformed stack must be preserved and flagged, not silently multiplied or destroyed.
- Full inventories never cause lore items to drop on the ground.
- Offline delivery and mass campaigns survive restarts indefinitely.
- Distribution cancellation stops pending recipients and keeps already delivered instances.
- Deleted definitions disappear from normal GUIs/tab completion, while a minimal hidden deleted-ID marker remains so restored stale copies can still be removed safely.
- All repository reads are paged and bounded. All workers, queues, batches, retries, caches, reconciliation jobs, GUI pages, and shutdown drains have explicit limits.
- Optional integrations fail independently and must not corrupt authoritative LoreItems state.
- Avoid reflection and console-command dispatch as plugin integration mechanisms.
- Avoid broad static-analysis suppressions. Document narrow false-positive suppressions.
- Do not claim tests passed, CI passed, Codacy is A-grade, or live behavior works without direct evidence.

## Git and PR workflow

- Never commit directly to `main`.
- Reuse the active phase branch instead of opening duplicate PRs.
- Keep new implementation PRs as drafts.
- Use logical commits with focused messages.
- Do not mix unrelated cleanup or later phases into the current PR.
- Inspect the complete diff before finishing.
- Run the strongest checks available and record exact commands/results.
- Inspect GitHub Actions after pushing and fix in-scope failures.
- Do not merge PRs unless the user explicitly authorizes that merge in the current chat.

The PR body must explain:

- scope delivered and deliberately deferred;
- module/package graph and dependency direction;
- database migrations, indexes, constraints, transaction boundaries, and recovery states affected;
- thread ownership, queue bounds, per-tick budgets, and backpressure;
- tests/checks actually run and their exact results;
- CI/Codacy evidence actually visible;
- risks, limitations, and required live-server tests.

## Work-size control

Do not attempt the whole plugin in one chat or PR.

Work until one of these boundaries is reached:

- the current planned PR phase is complete and reviewed;
- an active PR's concrete CI/review defects are fixed;
- a genuine blocking conflict in the binding documents is found;
- available tooling cannot provide required build or CI evidence.

Do not start the next implementation phase in the same PR. Do not hide incomplete work behind placeholders while claiming the phase is complete.

## Required response at the end of a work session

Return a concise handoff containing:

- detected current phase;
- action taken and why it was the next safe step;
- repository and branch;
- draft PR number/link, or the existing PR continued;
- head commit SHA;
- important files/modules changed;
- migrations/state machines/API changes;
- tests and checks actually run;
- GitHub Actions and Codacy status actually observed;
- unresolved risks, review findings, or missing evidence;
- exact next step for the following chat.

Do not merely propose work when the tools allow you to perform it. Inspect the repository, make the in-scope progress, and leave a durable GitHub handoff for the next chat.
