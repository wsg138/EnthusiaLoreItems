# EnthusiaLoreItems

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/d6792921c5a74ac29f318e69780d53cd)](https://app.codacy.com/gh/wsg138/EnthusiaLoreItems/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

EnthusiaLoreItems is a Paper plugin for creating, distributing, protecting, locating, editing, and safely removing tracked lore items on the Enthusia SMP.

Implementation is underway through bounded, reviewable draft pull requests. The plugin is not production-ready until all planned phases, hardening, and live-server acceptance work are complete.

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
4. Keep internal definition and instance identifiers out of player-visible text.
5. Expose a stable plugin API for the later EnthusiaTags reward integration.
6. Maintain enforceable hexagonal boundaries and strong automated tests.
7. Keep queues, caches, workers, retries, queries, GUI pages, and per-tick work bounded.

## Start or resume a ChatGPT development chat

New chats use the repository's handoff reports instead of repeatedly inspecting the entire project.

Send this message with the GitHub connector enabled:

> Use the GitHub connector to open `wsg138/EnthusiaLoreItems`, read `CHATGPT_START_HERE.md`, read the current handoff, and continue the next unfinished safe step. Follow the repository documents as binding requirements. Do not ask me to restate the project.

The chat will:

- verify the small amount of live GitHub state that may have changed;
- read `handoffs/CURRENT.md` from the active PR branch;
- read the latest report and explicitly required earlier reports;
- inspect only the files relevant to the next task;
- add a new immutable report before ending.

The handoff workflow does not authorize automatic merging. Every merge still requires explicit user approval.

## Project and workflow documents

- [Universal ChatGPT entrypoint](CHATGPT_START_HERE.md)
- [Current chat handoff](handoffs/CURRENT.md)
- [Handoff workflow](handoffs/README.md)
- [Handoff history](handoffs/INDEX.md)
- [Production requirements](REQUIREMENTS.md)
- [Architecture](docs/architecture.md)
- [Staged implementation plan](docs/implementation-plan.md)
- [Original phase-one implementation prompt](CHATGPT_IMPLEMENTATION_PROMPT.md)

Implementation remains split into separate phases. A chat must complete and review the active phase without silently beginning later gameplay features in the same pull request.
