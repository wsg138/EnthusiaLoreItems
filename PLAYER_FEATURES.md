# EnthusiaLoreItems player feature reference

EnthusiaLoreItems is **not currently deployed on the production Enthusia SMP**. The latest server-state snapshot contains no active EnthusiaLoreItems plugin directory, and the project README still marks implementation as underway/not production-ready.

This document describes the intended/implemented feature contract for future wiki generation. Until production acceptance/cutover is recorded, these features must be labeled **planned / pre-release**, not live.

## Purpose

The plugin creates and tracks unique or limited lore/collectible items that are meant to persist as real historical objects on the SMP rather than ordinary replaceable Minecraft items.

Core goals include:

- creating lore-item definitions from an exact item template,
- distributing tracked instances to selected players,
- preserving identity across inventories, containers and legitimate movement,
- finding/reviewing tracked copies,
- editing a definition and safely rolling changes to existing instances,
- protecting important items from accidental loss where configured,
- detecting/repairing ambiguous or duplicate state,
- safely retiring/removing tracked items,
- exposing a stable API for other Enthusia systems such as reward/tag integrations.

## Item identity

Lore identity is stored in persistent item metadata rather than depending on visible name/lore text. Player-visible text can therefore remain natural while the plugin tracks an internal definition/instance identity separately.

The system distinguishes:

- **definition** — what a lore item is supposed to be,
- **instance** — one tracked copy/issued object,
- **revision** — version of the definition/template applied to instances.

Internal IDs should not normally be exposed in player-facing lore.

## Creation and exact templates

Staff tooling is designed to create a lore-item definition from the exact held Minecraft item when appropriate, preserving relevant item metadata rather than rebuilding only a simplified material/name/lore approximation.

The editor/runtime is intended to support controlled changes to properties while keeping revision history and rollout bounded/recoverable.

## Distribution

Tracked lore items can be queued/distributed to selected players. Delivery is designed to be durable rather than a one-shot `give` call:

- offline recipients can remain queued,
- full inventories do not silently destroy the item,
- delivery state survives restart,
- duplicate/ambiguous issuance is guarded,
- Java and Floodgate/Bedrock recipients are part of the acceptance requirements.

This matches the original Enthusia use case of assigning specific collectible/lore items to a defined list of people and continuing delivery until each player has safely received theirs.

## Tracking and containers

The architecture tracks legitimate movement of lore items without force-loading chunks. It is designed to handle relevant inventory/container contexts, including shulker semantics, while bounding scans and per-tick work.

A location/index entry is evidence about where an instance was last observed, not permission to synchronously force-load an arbitrary world chunk.

## Protection

Definitions/features can attach protection behavior to tracked items. The exact protection rules are definition-driven rather than every lore item necessarily becoming indestructible.

Safety work includes conversion/protection acceptance coverage and explicit handling of destructive lifecycle operations so staff actions do not casually create loss or duplicate state.

## Editing and revision rollout

Changing a lore-item definition is treated as a revisioned operation. Existing tracked instances can be migrated/updated through a durable, bounded rollout instead of scanning the whole server in one synchronous operation.

The design preserves enough state for review/retry/rollback and handles late/temporarily unavailable copies without force-loading their chunks.

## Duplicate and anomaly handling

The system is intentionally conservative when it finds ambiguous identity or duplicate-like states. It should surface/review anomalies rather than guessing destructively.

Acceptance work covers ambiguous mutations, tracking contracts, mixed lifecycle cases, exact removal, late copies and other situations where a simplistic item tracker could dupe or delete collectible items.

## Removal / retirement

Staff can retire/remove lore items through controlled lifecycle operations. Exact tracked removal is distinct from broadly deleting every Minecraft item that happens to have the same visible name/lore.

Because items may be offline, nested, unloaded or otherwise temporarily unavailable, destructive operations are designed to be durable/recoverable rather than assuming the entire world can be synchronously scanned.

## Staff commands

The requirements/project command surface centers on `/loreitem`, with workflows for areas such as:

- creating definitions,
- spawning/distributing tracked instances,
- listing definitions/items,
- inspecting item/instance information,
- editing,
- reloading safe configuration,
- administrative duplicate/force/recovery operations.

Exact final syntax should be taken from the released plugin command metadata once the production build is finalized. Do not publish development-only command forms as permanent player/staff documentation before release.

## Other Enthusia integrations

A stable API is part of the design so systems such as EnthusiaTags/reward unlocks can award lore items without bypassing the lore plugin's identity/delivery guarantees.

The lore plugin should remain the authority for tracked item identity and lifecycle; integrations should not create hand-built lookalikes.

## Production-status rule

For future wiki automation, this repository has status **pre-release** until all of the following are true:

1. the project itself marks a release as production-ready,
2. the production server snapshot shows the plugin deployed,
3. any required migration/acceptance gates have passed.

Until then, exclude it from lists of current player features or clearly mark it as upcoming.