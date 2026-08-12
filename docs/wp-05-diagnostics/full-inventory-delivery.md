# WP-05 diagnostic evidence — full-inventory delivery deferral

**Acceptance credit:** NONE

This diagnostic uses a real Java 1.21.11 protocol/Bukkit player but offline authentication. It is defect-discovery evidence for the `ACC-CORE-005` state machine, not formal production-identity acceptance.

## Exact run
- Source/fixed-head harness: `fc4e6ed65b9b13db299389ea916f5d195dd6ca70`
- Workflow run: `31222689118`
- Job: `93010722076`
- Artifact: `9011000648`
- Artifact digest: `sha256:972582b601e44e94e50411832d76ea96ca8f0f170a3a8be8fe1a184406a3c6a8`
- Test jar SHA-256: `0b21cadc31a3e919e85ab12aa062162643f364a5c23dfdf84d039150ca3fff64`
- Client: Mineflayer 4.35.0, Java protocol 1.21.11, offline authentication
- Diagnostic player UUID: `44ac0094-7ccf-321c-8f41-7ad33f7a2bc6`

## Path exercised
1. Real protocol player created definition `acc_full_inventory` from a held diamond sword.
2. All 36 normal storage slots were replaced with full stone stacks.
3. A give was queued while inventory was full.
4. After an actual worker attempt, SQLite contained one instance and one delivery still `PENDING` with `attempt_count=1`.
5. Server probed for an overflow item entity; none existed. Client saw zero diamond swords while full because the definition source item had been replaced during inventory filling.
6. Exactly one hotbar slot was replaced with air.
7. Player disconnected and rejoined, providing a natural join wakeup.
8. Client then physically observed exactly one diamond sword; SQLite showed the one delivery `COMPLETED`, one instance total.
9. Server restarted again and the same player rejoined. Client still observed exactly one diamond sword.
10. Final SQLite state: one delivery total, one completed, zero review-required, one instance; integrity `ok`, no foreign-key violations.

## Evidence highlights
Full state:
```json
{"deliveries":[{"state":"PENDING","attempt_count":1}],"instances":1}
```

Delivered state:
```json
{"deliveries":[{"state":"COMPLETED","attempt_count":2}],"instances":1}
```

Replay state:
```json
{"deliveries":[1,1,0],"instances":1,"integrity":["ok"],"foreign_keys":[]}
```

Client evidence:
- `FILLED_ITEMS 36`
- `SWORDS_WHILE_FULL 0`
- `SWORDS_AFTER_SPACE 1`
- `SWORDS_AFTER_REPLAY 1`

## Finding
No LoreItems implementation failure was observed. The fixed head persisted full-inventory deferral, did not create an overflow item entity, delivered exactly once after one valid slot opened and a natural join wakeup occurred, and did not duplicate on a subsequent restart/rejoin.

## Conclusion
This is strong safety regression/defect-discovery evidence for the full-inventory delivery path, but it remains non-credit diagnostic evidence because the Java identity was not production-authenticated.
