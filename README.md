# EnthusiaLoreItems

EnthusiaLoreItems is a planned Paper plugin for creating, distributing, protecting, locating, editing, and safely removing tracked lore items on the Enthusia SMP.

The repository is currently in the architecture and requirements phase. No production implementation should begin until the planning pull request is reviewed and approved.

## Target runtime

- Java 21
- Paper/Leaf 1.21.11
- Single SMP server
- SQLite persistence
- Geyser/Floodgate compatibility

## Design priorities

1. Prevent item loss and accidental duplicate delivery.
2. Persist every destructive or distributive operation across restarts.
3. Never force-load chunks for tracking, editing, or deletion.
4. Keep internal definition and instance identifiers out of player-visible lore.
5. Expose a stable plugin API for the later EnthusiaTags reward integration.
6. Maintain clear hexagonal boundaries and strong automated test coverage.
7. Keep every queue, cache, worker, retry, query, and per-tick mutation budget bounded.

## Start a new ChatGPT development chat

Use [the universal ChatGPT entrypoint](CHATGPT_START_HERE.md). It tells a new chat to inspect the live repository and pull-request state, determine the first unfinished safe phase, continue an existing PR when appropriate, and leave a durable handoff.

The reusable user message is:

> Use the GitHub connector to open `wsg138/EnthusiaLoreItems`, read `CHATGPT_START_HERE.md`, determine the next unfinished safe step, and begin it. Follow the repository documents as binding requirements. Do not ask me to restate the project.

The entrypoint does not authorize automatic merging. A chat may create or continue draft PRs, fix CI/review defects, and mark work ready for review, but it must wait for explicit user authorization before merging.

## Planning documents

- [Universal ChatGPT entrypoint](CHATGPT_START_HERE.md)
- [Production requirements](REQUIREMENTS.md)
- [Architecture](docs/architecture.md)
- [Staged implementation plan](docs/implementation-plan.md)
- [Phase-one implementation prompt](CHATGPT_IMPLEMENTATION_PROMPT.md)

Implementation is intentionally split into reviewable pull requests. Each implementation chat must remain within the current planned phase and stop before beginning the next phase.
