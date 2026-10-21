# Read-only historical world-key format inventory — 2026-10-20

**INACTIVE DEVELOPMENT ONLY.** This augments the copied-SQLite outage preflight on draft #165. It does not look up worlds, compare Minecraft physical inventories, move items, rewrite SQLite rows, or authorize installation of a JAR.

The report now includes `location_key_format` per persisted current-state row and the aggregate `location_key_formats` counts:

- `LEGACY_WORLD_NAME_NEEDS_PROOF` — historical block keys shaped as `worldName:x:y:z` or `namespace:worldName:x:y:z` (including nested `root:BLOCK_CONTAINER:` prefix). These **cannot** be mapped to a modern world UUID from a name alone.
- `UUID_FORMAT_HISTORY_ONLY` — syntactically valid `block-v2:<UUID>:x:y:z` or `double-chest:<UUID>:x:y:z|x:y:z:left=0|1` keys; syntactic validity alone does not prove a present-day physical location or the chest's actual geometry.
- `UNRECOGNIZED_BLOCK_KEY`, `NON_BLOCK_LOCATION`, and `NO_RECORDED_LOCATION` — explicit further-review or unrelated categories. Malformed `block-v2:` or `double-chest:` prefixes do not silently fall back to a legacy world name.

The report distinguishes **current projection counts** in `location_key_formats` from **all recorded historical observation counts** in `historical_observation_key_formats` and `historical_observation_rows`. Both are read from the same WAL-consistent copied snapshot. Neither proves which old physical world ID or item location should be used today; the counts are a format census, **not validated migration candidates**. `verified_current_locations` always remains **zero** after the plugin outage. An actual physical world UUID, matching PDC instance ID, chest slot geometry, event generation and durable receipt are separately required for reconciliation. Do not rewrite old location strings or collapse provenance. Issue #103 remains open.

Only synthetic preflight tests have been added. The full 25-workflow exact combined-head CI acceptance and an independent physical Paper/SQLite + WAL restore rehearsal are still needed. Keep the live SMP, original SQLite, plugin directory and player data untouched.
