# LoreItems configuration reload

LoreItems supports an operator-facing hot reload for the settings that are declared reloadable in `docs/operator-guide.md`.

## Command

```text
/loreitems reload
```

Permission:

```text
enthusia.loreitems.admin.reload
```

The command is operator-only by default. Only one reload request is submitted at a time through the command surface.

## Safety contract

The reload path reads and validates a complete candidate `plugins/EnthusiaLoreItems/config.yml` snapshot off the server thread. The active snapshot is replaced atomically only after the candidate is valid.

If validation fails, the command reports that the reload was rejected and the previous complete snapshot remains active. Active deliveries, item mutations, template rollouts, destructive operations, and distribution work are not discarded by a configuration reload.

The database startup settings below are intentionally restart-only and cause a candidate reload to be rejected if they differ from the running snapshot:

- `database-busy-timeout-millis`
- `database-queue-capacity`
- `database-shutdown-timeout-seconds`

Do not use Bukkit/Paper `/reload` as a substitute for this command. Use `/loreitems reload` for reloadable LoreItems configuration and a clean server restart when changing restart-only settings.

## Operator procedure

1. Preserve the current `config.yml` or make a small backup copy.
2. Edit only the intended settings.
3. Run `/loreitems reload` from an authorized account or the server console.
4. Require an explicit `LoreItems configuration reload applied:` result before treating the candidate as active.
5. If the result is `LoreItems configuration reload rejected:`, correct the file; the last-known-good in-memory snapshot is still active.
6. For a restart-only setting, restore a valid file and schedule a clean restart instead of trying to force a hot reload.

WP-05 live acceptance verifies that a valid reload changes real nested-storage protection behavior without a server restart, that an invalid candidate leaves the last-known-good behavior active, and that durable queued delivery survives both reload attempts.
