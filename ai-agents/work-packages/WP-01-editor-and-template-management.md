# WP-01 — Editor and template management

## Objective

Complete the production administrative interface for editing lore-item templates and starting durable template-revision rollouts. Deliver the whole editor and template-management workflow; backend revision primitives without the complete operator workflow do not satisfy this package.

## Dependencies

- PR #9 is merged into live `main`.
- Existing definition, revision, template-codec, rollout, accessible-inventory, and naturally encountered entity update infrastructure is treated as baseline and must be reused or corrected rather than duplicated.

## Complete required scope

1. Add GUI navigation from the primary definition browser to a definition-specific template-management screen with current revision, instance count, anomaly count, pending-update count, and safe preview.
2. Add one bounded, permission-checked editor session per administrator. Chat-backed text input must support explicit submit, cancel, validation failure, timeout, disconnect, reload, and shutdown without applying an incomplete draft or leaking listeners/tasks.
3. Provide draft/preview/confirm behavior. No revision is persisted and no rollout begins until the administrator confirms the complete draft. Cancelling or failing validation leaves the current template unchanged.
4. Support these exact edit operations:
   - base material;
   - custom/item name, including clear, literal text, solid color, and multi-stop gradient;
   - lore line add, edit, remove, reorder, and clear, with per-line literal text, solid color, or multi-stop gradient;
   - enchantment add/update/remove with namespaced key and integer level validation;
   - enchantment tooltip visible/hidden;
   - glint override set true, set false, or unset;
   - damage value with material-valid bounds;
   - unbreakable state;
   - attribute modifier add/update/remove with attribute key, operation, amount, equipment slot/group, and stable modifier identity;
   - item model namespaced key set/clear;
   - maximum stack-size input and validation, while every tracked template and generated instance is normalized to one;
   - custom model data supported by the target Paper version;
   - dyed color for dyeable items;
   - potion base type, custom effects, and potion color;
   - armor trim material/pattern;
   - banner patterns;
   - player profile for player heads;
   - firework rocket and firework-star effects;
   - tooltip/item-flag controls exposed by the target Paper API for the preceding fields.
5. Keep the advanced `replace template from held item` path. It must exact-copy all Paper-supported components through the versioned codec, remove any LoreItems instance identity from the source snapshot, normalize amount and maximum stack size to one, preview the replacement, and require explicit confirmation.
6. Persist each confirmed edit as one immutable, monotonically increasing definition revision with actor and before/after audit evidence. The revision and durable rollout intent must commit atomically or not at all.
7. Queue every known active instance for the desired revision. Update accessible instances through bounded per-tick work and inaccessible instances only when naturally encountered. Preserve definition ID and instance UUID, verify before/after identity and revision, never force-load chunks, and move ambiguous outcomes to review instead of overwriting or retrying blindly.
8. Show rollout status and counts in the template-management interface. WP-01 may show status and link to recovery details; pause/resume and destructive queue controls are WP-02.
9. Reject all edits in degraded/read-only storage mode. Paper object access/serialization remains on the server thread; database/filesystem work remains off-thread; no live Bukkit object crosses the async boundary.
10. Add permissions, command/GUI routing, tab-completion behavior, operator messages, and development/operator documentation for every editor path.

## Exact acceptance criteria

- An authorized administrator can complete every listed edit through the GUI/chat workflow and can replace from a held item without using internal IDs.
- Unauthorized, console-only where a player context is required, stale-session, invalid-input, cancelled, timed-out, disconnected, reload, shutdown, and degraded-mode paths apply no revision and start no rollout.
- One confirmation creates exactly one revision and one logical rollout, even after duplicate clicks, callback replay, restart, or retry.
- The new revision preserves all unchanged template components and hidden instance identity during rollout.
- Existing accessible player, Ender Chest, physical-container, nested shulker/bundle, dropped-item, item-frame/glow-frame, and armor-stand instances update within configured bounded work when their scopes are already accessible.
- Offline/unloaded instances remain durably pending and update on the next supported natural observation without force loading.
- Malformed stacks, duplicate-conflict instances, identity mismatch, stale revision, and ambiguous physical outcomes are preserved and routed to anomaly/review state; they are not silently normalized, deleted, or assigned new identity.
- GUI/query results are paginated and no edit path performs unbounded history or instance loads.
- Documentation lists each supported component and identifies replace-from-held as the exact-copy fallback for components not explicitly exposed.

## Required automated tests

- Pure application/domain tests for editor draft validation, confirmation idempotency, monotonic revision creation, stale revision rejection, and audit intent.
- Paper adapter tests for every exact edit operation, gradient/name/lore parsing, component round trips, preview/cancel/session lifecycle, permission routing, and replace-from-held identity stripping.
- SQLite integration tests proving atomic revision-plus-rollout creation, rollback on failure, restart recovery, duplicate confirmation idempotency, pagination, and desired/applied revision persistence.
- Existing and new rollout tests covering player inventory, Ender Chest, physical containers, nested shulker/bundle paths, dropped entities, item frames, glow item frames, armor stands, offline/unloaded deferral, natural re-encounter, malformed/duplicate evidence, bounded budgets, and no sibling-instance corruption.
- Reload/shutdown tests proving sessions close safely and active durable rollout work survives.
- Architecture tests proving domain/application do not import Bukkit, Paper, JDBC, YAML, or GUI classes.
- Full `gradle --no-daemon clean check`, repository-tool tests, new-code complexity check, and exact-head Codacy.

## Required review and verification gates

- Independent review against the full WP-01 contract and the repository-wide loss/duplication/threading/bounds/reload/architecture risk list.
- Direct review of editor session cleanup, component preservation, PDC identity preservation, idempotent confirmation, and rollout ambiguity handling.
- Exact-head GitHub Actions and exact-head Codacy success after the final commit.
- No requested changes and zero unresolved review threads.
- Normal merge commit followed by live `main` verification.

## Explicit exclusions

- Exact-instance removal, purge, full definition deletion, and pause/resume/review mutation controls; these are WP-02.
- Mass-distribution campaign workflows; these are WP-03.
- Manual live-server acceptance or production release; these are WP-05.
- Definition lookup-key renaming, custom gameplay powers, arbitrary NBT editing, or identity exposure in visible item data.
- Starting WP-02.

## Definition of complete

WP-01 is complete only when every required editor/component path, exact-copy fallback, durable revision/rollout behavior, tests, documentation, and review gate is present in one normally merged WP-01 PR; live `main` is verified; queue/state/handoff records are updated; and the worker stops without beginning WP-02.

## Expected status transitions

`READY -> IN_PROGRESS -> IN_REVIEW -> VERIFYING -> MERGED -> COMPLETE`

Review or verification failure returns to `IN_PROGRESS` on the same branch and PR.

## Branch and PR naming

- Branch: `agent/wp-01-editor-template-management`
- PR title: `WP-01: complete editor and template management`

## Exact next package

WP-02 — destructive administration and queued-operation controls. It remains blocked until WP-01 is `COMPLETE` and requires a separate assignment.
