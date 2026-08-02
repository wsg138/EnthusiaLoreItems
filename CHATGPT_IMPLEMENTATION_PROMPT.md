# Prompt for the first implementation chat

Copy everything below into a new ChatGPT chat with the GitHub connector enabled.

---

You are implementing the first phase of a production Minecraft plugin through GitHub.

Repository: `wsg138/EnthusiaLoreItems`

Reference repositories:

- `wsg138/EnthusiaStaff` for examples of strong hexagonal boundaries, lifecycle safety, durable workflows, architecture tests, and documentation. Do not copy its multi-server/MariaDB complexity when it is unnecessary.
- `wsg138/EnthusiaTags` only to understand the future reward integration boundary. Do not modify EnthusiaTags in this phase.

Your task is **PR 1 — Foundation and durable core only**. Do not attempt the complete plugin in one pass.

Before changing code:

1. Read `README.md`, `REQUIREMENTS.md`, `docs/architecture.md`, and `docs/implementation-plan.md` in full.
2. Inspect the relevant architecture, build, lifecycle, persistence, testing, and static-analysis patterns in EnthusiaStaff.
3. Confirm the current head of `main` and inspect all existing files and commits.
4. State any conflict you find between the documents. Otherwise treat them as binding requirements.
5. Create a feature branch and a draft pull request. Do not commit directly to `main`.

Implement the complete PR 1 scope from `docs/implementation-plan.md`:

- Java 21 Gradle build targeting Paper/Leaf 1.21.11;
- enforceable hexagonal module or package boundaries;
- pure domain identities, aggregates, state machines, validation, and result types;
- application use cases and ports without Bukkit, JDBC, YAML, or GUI imports;
- immutable validated configuration and an atomic reload boundary;
- SQLite in WAL mode with forward migrations, foreign keys, transactions, indexes, bounded busy timeout, and clean lifecycle;
- durable schema/repositories for definitions, revisions, instances, observations, current state, anomalies, mutations, direct deliveries, campaigns, recipients, external idempotent deliveries, deleted-definition markers, and audit events;
- bounded worker/queue abstractions and metrics interfaces;
- versioned item-template serialization and hidden PDC identity codec boundaries, with only the Paper implementation needed to prove safe round-trip behavior;
- a versioned Bukkit service API for idempotent external delivery, while making it clear that actual inventory delivery is not active until a later PR;
- architecture tests, domain unit tests, SQLite integration tests, migration tests, and restart/idempotency tests appropriate to this phase;
- GitHub Actions and static-analysis configuration intended to produce an A-grade Codacy result without broad suppressions;
- operator/developer documentation for building, database location, migrations, degraded startup behavior, and the limitations of this foundation phase.

Non-negotiable engineering rules:

- Do not access Bukkit/Paper objects asynchronously.
- Do not perform database or filesystem I/O on the server thread.
- Do not force-load chunks.
- Do not use unbounded executors, queues, caches, retries, result sets, or repository methods returning all rows.
- Do not retain live `Player`, `Inventory`, `Entity`, `Chunk`, or mutable `ItemStack` references across async boundaries.
- Persist intent before destructive or distributive side effects in later phases; design the state machines and schema for this now.
- Use idempotency keys and compare-and-set/claim-token transitions so restart or duplicate workers cannot apply one operation twice.
- Enter a safe read-only/degraded mode when durable storage is unavailable. Do not pretend writes succeeded.
- Internal definition IDs and instance UUIDs must remain out of visible item name/lore/tooltips.
- Avoid reflection and command dispatch as integration mechanisms.
- No implementation claim is valid without direct test or CI evidence.
- Avoid large manager/god classes. Wiring belongs in bootstrap; policy belongs in domain; orchestration belongs in application; platform/storage details belong in adapters.
- Keep Codacy findings low by design. Use narrow documented suppressions only for genuine false positives.

Build and verification expectations:

- Run the full local test/check task available in the project.
- Inspect GitHub Actions results after pushing.
- Fix failures that are within this PR's scope.
- Add architecture tests that forbid platform/persistence imports in core code and detect dependency cycles.
- Add tests that prove schema creation/migration, unique constraints, idempotent external request acceptance, bounded paging, restart recovery of nonterminal records, and safe storage-unavailable behavior.
- Do not claim live Paper behavior has been tested unless there is direct live-server evidence.

PR requirements:

- Keep the PR as a draft.
- Use logical commits rather than one enormous commit.
- Explain the module graph, database schema, state machines, thread boundaries, queue bounds, and deferred features in the PR body.
- List exact commands/checks run and their results.
- Clearly identify anything not verified.
- Do not merge the PR.

Stop after PR 1 is complete. Return:

- branch name;
- draft PR number and link;
- head commit SHA;
- files/modules added;
- database migrations and important constraints;
- tests and checks actually run;
- CI/Codacy status that is directly visible;
- remaining risks or unverified assumptions;
- a concise handoff for an independent reviewer.

---
