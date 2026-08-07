# One-use mass distributions

WP-03 adds durable, one-use lore-item campaigns for handing one pinned lore definition revision to a bounded list of recipients without requiring every player to be online at campaign start.

## Safety model

The database is authoritative. Group files and their active/completed/cancelled locations are operator-visible markers, not the source of truth for campaign state.

A campaign start is durable before its source file is moved to an active marker. If the filesystem move fails, the campaign remains active in SQLite and marker reconciliation repairs the visible file state later. The same source fingerprint cannot be started again after a durable campaign has claimed it.

Each recipient is independent. A campaign never drops an item on the ground and never treats an inventory-full player as delivered. Delivery remains pending until a bounded retry or player wakeup can safely continue it.

## Group files

Place source files directly in `plugins/EnthusiaLoreItems/groups/`.

LoreItems creates and owns:

- `groups/`
- `groups/completed/`
- `groups/cancelled/`

Only direct `.yml` files without the internal `.active-<campaign-uuid>.yml` marker are discoverable as new sources.

Example:

```yaml
display-name: Summer 2026 reward
players:
  - JavaPlayer
  - '*BedrockPlayer'
  - 01234567-89ab-cdef-0123-456789abcdef
```

Supported top-level keys are exactly `display-name` and `players`. Unknown keys are rejected rather than ignored.

Recipient values support:

- Java-style player names;
- Floodgate-style names beginning with `*`;
- explicit UUIDs.

The original value is retained for operator output and audit evidence. Name matching is case-insensitive for identity binding. Duplicate normalized recipients, including case-only duplicates and equivalent UUID spellings, are rejected before start.

### Validation and workload bounds

Validation is fail-closed and bounded:

- group files must be safe, readable regular files directly inside `groups/`;
- symbolic links and path traversal are rejected;
- each source file is limited to 1 MiB;
- each source file is limited to 100,000 recipients;
- a directory reload inspects at most 10,000 directory entries, including irrelevant files;
- malformed YAML and invalid files produce per-file diagnostics instead of becoming partial campaigns.

Filesystem parsing, hashing, marker moves, and cached-name resolution run off the server thread.

## Preview and confirmation

Starting a campaign is intentionally two-step.

1. `/loredistribution preview <group.yml> <definition-key>`
2. `/loredistribution confirm <campaign-uuid>`

Preview selects the current active definition revision, validates the source, resolves only already-cached name identities, and builds an immutable candidate. It does not start delivery.

Confirmation belongs to the same actor that created the preview. Before starting, confirmation re-reads and revalidates the source. A changed fingerprint or definition revision invalidates the preview instead of silently changing the campaign.

The durable start transaction records:

- the campaign UUID;
- the selected definition and pinned revision;
- the source name, display name, and fingerprint;
- the immutable recipient snapshot and original values;
- resolved UUIDs known at start;
- actor identity and start audit evidence.

Only after that transaction commits does the source move to its active marker.

## Identity binding

Explicit UUID recipients are authoritative immediately.

Names that are already known in the server cache can be bound during preview without any network lookup. Unknown names remain `UNRESOLVED` indefinitely; campaign start does not block on Mojang or another remote identity service.

When a player joins, unresolved name recipients are compared case-insensitively to the joining identity. This includes configured Floodgate-style names. The durable binding is atomic and can only happen once. After binding, the stored UUID is authoritative for physical delivery.

Late identity work is paginated and bounded per server tick.

## Recipient states

Every campaign recipient is always in exactly one of these states:

- `UNRESOLVED`
- `QUEUED_OFFLINE`
- `QUEUED_INVENTORY_FULL`
- `RESERVED_IN_FLIGHT`
- `REVIEW_REQUIRED`
- `DELIVERED`
- `CANCELLED`

Campaign status reports exact per-state counts plus:

- `total`: every immutable recipient snapshot row;
- `remaining`: every row that has not reached a terminal state;
- `unresolved`: recipients still waiting for a durable UUID binding.

`DELIVERED` and `CANCELLED` are terminal recipient outcomes. `REVIEW_REQUIRED` is deliberately non-automatic: it prevents ambiguous crash or mutation outcomes from causing duplicate physical delivery.

## Exactly-once delivery protocol

Delivery is bounded by the configured claim batch and per-tick mutation budget.

For a deliverable recipient, LoreItems:

1. durably claims the recipient with a finite lease;
2. creates a fresh lore-instance identity pinned to the campaign revision;
3. durably links that instance to the claimed recipient and records queued-delivery evidence;
4. returns to the server thread for the physical inventory mutation;
5. inserts into a real player storage slot only when space exists;
6. verifies the exact lore-instance identity and one-item stack in that slot;
7. durably records the inventory observation and marks the recipient `DELIVERED`.

If the player is offline, the recipient returns to `QUEUED_OFFLINE`. If storage is full, it returns to `QUEUED_INVENTORY_FULL`. No item is spawned or dropped.

Player join, inventory close, and player drop events wake eligible pending delivery work. Periodic polling and expired-claim recovery provide the restart/backpressure path when no wake event occurs.

### Crash behavior

An expired claim without a prepared lore instance can safely return to pending delivery.

An expired claim with a prepared instance is not automatically retried. It enters `REVIEW_REQUIRED`, and its current-state evidence becomes unresolved. This is the conservative boundary for the dangerous crash window where a physical insertion may have happened but durable completion is uncertain.

That rule favors avoiding duplicate rare items over blind automatic retry.

## Pause, resume, and cancel

Commands:

- `/loredistribution pause <campaign-uuid>`
- `/loredistribution resume <campaign-uuid>`
- `/loredistribution cancel <campaign-uuid>`

Pause/resume state and their audit records commit atomically in SQLite.

Cancellation uses a server-thread fence before the durable state change so newly claimed or prepared deliveries cannot race ahead of the operator action. The durable cancellation transaction:

- marks the campaign `CANCELLED`;
- marks non-delivered pending recipients `CANCELLED`;
- preserves already `DELIVERED` recipients;
- writes cancellation audit evidence with the number of recipients cancelled.

A physical item that was already inserted immediately before cancellation is allowed to finish durable completion. This prevents a real delivered item from becoming an untracked duplicate opportunity.

Prepared or claimed work that cannot be safely terminalized during a cancellation fails toward no further physical insertion and is recovered conservatively.

## Marker reconciliation and restart

The runtime periodically reads campaign state from SQLite in bounded pages and reconciles filesystem markers:

- `ACTIVE`/`PAUSED` campaigns require an active marker;
- `COMPLETED` campaigns move to `groups/completed/`;
- `CANCELLED` campaigns move to `groups/cancelled/`.

If the original source or active marker disappeared after the database committed, reconciliation atomically synthesizes a non-reusable operator marker containing the durable campaign ID, source name, and source fingerprint. Terminal reconciliation can move that reconstructed marker into `completed/` or `cancelled/`. A changed replacement source is left untouched and is never substituted for the durable campaign snapshot.

Filesystem marker repair never rewrites durable campaign state from the filesystem. Startup resumes durable recipients and expired claims from SQLite, and a marker is never used to decide whether a previously committed campaign should deliver again.

## Operator commands

`/loredistribution` provides:

- `reload [page]` — validate and list discoverable sources;
- `inspect <group.yml>` — inspect one validated source;
- `preview <group.yml> <definition-key>` — create an actor-scoped start preview;
- `confirm <campaign-uuid>` — explicitly start that preview;
- `campaigns [page]` — list campaigns;
- `status <campaign-uuid>` — show campaign state and exact recipient counts;
- `recipients <campaign-uuid> [state|all] [page]` — inspect bounded recipient pages;
- `pause <campaign-uuid>`;
- `resume <campaign-uuid>`;
- `cancel <campaign-uuid>`;
- `reconcile [page]` — run a bounded DB-authoritative marker reconciliation page.

All list surfaces are bounded by the configured page size.

Campaign recipients in `REVIEW_REQUIRED` also appear in the existing `/loreitems recovery [page]` operator view alongside WP-02 direct-delivery and mutation recovery work.

## Permissions

- `enthusia.loreitems.admin.distribution.inspect`
- `enthusia.loreitems.admin.distribution.start`
- `enthusia.loreitems.admin.distribution.control`

All default to operators through `plugin.yml`.

## Metrics

WP-03 records campaign counters and gauges through the same `MetricsPort` used by the storage runtime. Metric keys include:

- `distribution.delivery.claim_batch`
- `distribution.delivery.last_claimed`
- `distribution.delivery.prepared`
- `distribution.delivery.deferred_offline`
- `distribution.delivery.deferred_inventory_full`
- `distribution.delivery.delivered`
- `distribution.delivery.cancelled`
- `distribution.delivery.review_required`
- `distribution.delivery.expired_claim_recovery`
- `distribution.delivery.last_recovered`
- `distribution.delivery.last_woken`
- `distribution.campaign.paused`
- `distribution.campaign.resumed`
- `distribution.campaign.cancelled`
- `distribution.campaign.last_cancelled_recipients`
- `distribution.recipients.unresolved`
- `distribution.recipients.review_required`
- `distribution.recipients.remaining`
- `distribution.review.last_page_size`

The current foundation can use a no-op metrics backend; WP-03 does not create a parallel telemetry system.

## Lifecycle behavior

The distribution runtime activates only after SQLite reaches writable state. In degraded/read-only startup, campaign mutation commands and workers do not activate.

Campaign workers use the existing bounded database executor and lifecycle worker executor. They do not create a separate unbounded thread pool.

On plugin shutdown, marker, identity-binding, delivery, command, and campaign-administration service components close before the shared SQLite runtime drains. A partially failed startup unregisters any campaign administration service it registered and closes any workers that already started.

`/loredistribution reload` reloads group-source discovery and validation only. Existing campaign state, pinned revisions, recipient snapshots, and active delivery claims are never rebuilt from mutable files during reload.
