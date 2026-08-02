# Staged implementation plan

The plugin must be implemented through reviewable draft pull requests. Do not combine the entire system into one large PR.

## PR 1 — Foundation and durable core

Deliver:

- Java 21 Gradle build and module/package boundaries;
- Paper/Leaf 1.21.11 plugin bootstrap;
- pure domain identities, states, and policies;
- application ports and use-case interfaces;
- immutable validated configuration and safe reload boundary;
- SQLite WAL storage, migrations, transactions, indexes, and lifecycle;
- definition, revision, instance, observation, audit, mutation, direct-delivery, campaign, recipient, anomaly, and deleted-marker persistence models;
- versioned item-template and hidden-identity codec interfaces, with focused Paper implementations where required;
- bounded executors/queues and operational metrics interfaces;
- Bukkit service API contract for idempotent external delivery, with unavailable/not-yet-active implementation as appropriate;
- architecture tests and SQLite integration tests;
- CI/static-analysis configuration suitable for Codacy.

Do not claim production readiness. No broad GUI/listener surface is required in this PR beyond what is necessary to validate item codec behavior and lifecycle wiring.

## PR 2 — Creation, adoption, direct delivery, and protection

Deliver:

- create definition from held item;
- adopt held item into an existing definition;
- direct give to self, online player, or queued offline player;
- inventory-full persistence;
- join/restart recovery;
- hidden PDC identity and forced unstackability;
- environmental protection, durability protection, void terminal loss;
- item-frame/glow-frame/armor-stand support and mob pickup prevention;
- initial commands, permissions, and audit views;
- duplicate/malformed-stack detection and five-minute staff warnings.

## PR 3 — Tracking and reconciliation

Deliver:

- event-driven player, Ender Chest, physical container, dropped item, display entity, shulker, and bundle observation;
- natural chunk-load reconciliation with strict budgets;
- confirmed-now versus last-confirmed status;
- nested location paths;
- paginated definition/instance/location GUIs;
- anomaly inspection and explicit duplicate resolution workflow;
- performance metrics and backpressure behavior.

## PR 4 — Editing and destructive administration

Deliver:

- chat-backed item editor and GUI navigation;
- names, colors, gradients, lore, enchantments, hidden enchantments, glint, damage, unbreakable state, attributes, models, and common components;
- replace-template-from-held-item advanced path;
- durable revision rollout to all instances;
- remove exact instance;
- purge all instances while retaining definition;
- full deletion with confirmation, queued physical removal, hidden deleted-ID marker, and history access;
- pause/resume/review for queued updates and removals.

## PR 5 — One-use mass distributions

Deliver:

- default group directories and YAML validation;
- one-use source fingerprinting;
- active/completed/cancelled marker lifecycle;
- immutable SQLite recipient snapshot;
- Java and Floodgate `*`-prefixed name handling;
- unresolved future-player binding, including first join years later;
- persistent delivery, restart resume, full-inventory waiting;
- status GUI/command with all required counts;
- pause, resume, and cancel while retaining delivered items;
- tests for duplicate starts, filesystem/database recovery, and long-lived unresolved names.

## PR 6 — Production hardening

Deliver:

- failure injection and restart tests across every state machine;
- queue saturation/backpressure tests;
- migration and upgrade tests;
- live-server acceptance checklist and results;
- performance profiling with realistic tracked-item/container/campaign counts;
- documentation, operator recovery instructions, backup/rollback procedure;
- Codacy cleanup without broad suppressions;
- final stable public API version.

## Separate repository phase — EnthusiaTags integration

Only after the stable lore-item API exists:

- add a dedicated `LORE_ITEM` reward action to EnthusiaTags;
- call the Bukkit service API rather than dispatching commands;
- use the Tags reward claim identity as the external idempotency key;
- handle unavailable LoreItems service without marking the reward delivered;
- recover safely after either plugin reloads or the server restarts;
- rebalance the reward configuration separately from the API change.

Every PR requires an independent review for item loss, duplicate creation, main-thread blocking, unbounded work, unsafe reload/shutdown, architecture violations, and misleading test claims before proceeding.
