# LoreItems Spark baseline (pre-deployment)

Sources: [SMP Spark profile review](https://github.com/wsg138/Enthusia-AI/blob/fix/discord-command-safe-upsert-20261008/deploy/bloom/SPARK-SMP-STACK-ROOT-CAUSE-REVIEW-20261010.md), [AI-off profile](https://spark.lucko.me/vKZdMAkBX9), [AI-on profile](https://spark.lucko.me/92tX2g1hoK).

Sampler: ASYNC execution, 10ms samples, SIMPLE aggregation, BY_POOL, one-minute windows. Inclusive stack times overlap; **never add them as exclusive CPU costs**.

| Metric | AI off, last minute (14 players) | AI on, slow minute 2 (14 players) | AI on, slow minute 3 (14 players) |
|---|---:|---:|---:|
| TPS | 19.92 | 16.92 | 16.67 |
| Ticks | 1194 | 980 | 980 |
| MinecraftServer.tickServer sampled seconds | 21.21 | 52.91 | 45.60 |
| All main-thread scheduled tasks sampled seconds | 6.63 | 18.46 | 17.00 |
| LoreItems template scan sampled seconds | 2.33 | 9.64 | 10.20 |
| LoreItems deferred actions sampled seconds | 2.99 | 5.70 | 4.06 |
| Entity ticking sampled seconds | 7.98 | 14.58 | 10.99 |
| Chunk ticking sampled seconds | 3.23 | 9.80 | 8.11 |

This identifies LoreItems scanning as a meaningful hotspot. Other engine work and AI-related contention also changed; these profiles **do not** isolate the causal effect of any one plugin. These are **not** comparisons of optimized versus deployed LoreItems binaries.

Artifact lineage: historical PR41 head `91a57261` (SMP Oct9 snapshot) versus combined candidate PR52 head `e50ae612` (24/24 CI). The packaged plugin descriptor and 11 SQL migration files match byte-for-byte. The candidate remains **unmerged, not staged and not deployed**.

Telemetry review: proposed optimization counters `tracking.deferred_player_scan_coalesced`, `tracking.periodic_chunk_scan_coalesced`, `tracking.deferred_player_scan_schedule_rejected` are **not retained** by PR52's existing process-local TrackingMetrics. Draft [PR60](https://github.com/wsg138/EnthusiaLoreItems/pull/60) adds collection and operator visibility, not yet part of the candidate.

Before/after evaluation: record exact JAR checksum, time, player count, loaded chunks, hopper/container activity, Spark MSPT distribution and sampled stacks, tracking queue depth/rejections/truncations, duplicate/malformed identity events and pending work health. Compare matched workloads only. Treat unavailable metrics as missing, not zero. Require source-compatible release, current remote hash confirmation and DB safety review before deployment.
