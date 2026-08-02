# EnthusiaLoreItems

EnthusiaLoreItems is a planned Paper plugin for creating, distributing, protecting, locating, editing, and safely removing tracked lore items on the Enthusia SMP.

The repository is currently in the architecture and requirements phase. No production implementation should begin until the planning pull request is reviewed and approved.

## Target runtime

- Java 21
- Paper/Leaf 1.21.11
- Single SMP server
- SQLite persistence

## Design priorities

1. Prevent item loss and accidental duplicate delivery.
2. Persist every destructive or distributive operation across restarts.
3. Never force-load chunks for tracking, editing, or deletion.
4. Keep internal definition and instance identifiers out of player-visible lore.
5. Expose a stable plugin API for the later EnthusiaTags reward integration.
6. Maintain clear hexagonal boundaries and strong automated test coverage.

Detailed requirements and architecture documents will be proposed through a planning pull request before code is added.
