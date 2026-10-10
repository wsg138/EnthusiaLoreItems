# PR #59 offline binary release-readiness comparison — 2026-10-10

This is an **independent artifact inspection**, not a live-server deployment or a production acceptance.

Downloaded exact GitHub Actions JAR artifacts and recomputed their SHA-256 values from ZIP payload bytes:

| Role | Source | JAR bytes | SHA-256 |
| --- | --- | ---: | --- |
| October 9 historical SMP snapshot baseline | PR #41 / 91a57261d2627c67e05d0a470a73c40734104889 | 13,240,936 | 5c7fceebc723e5d9293e45fc689528effc682e9a1433afb24c93ff7b22a17580 |
| Prior integrated candidate | PR #52 / e50ae6128047c9ab6cc1deb54408c099774a7090 | 13,284,167 | 3b51979c12a2b0f325e754fbc4f66436cabaddbf57fd0bc48d9d3278a74a1852 |
| New integrated candidate | PR #59 / 34557d36d1e3517a365b8d3793f84d3044c6c0ea | **13,287,828** | **bccbe03f7b8f7f849318429ca34630f98ccad18c2a49ecbed55619d3bf672530** |

- PR #59 exact binary from Actions run **38078123176**, artifact **11678884013**, packaged SOURCE_SHA matches the PR head, and its bundled checksum agrees with the recomputed SHA-256.
- The JAR manifest declares plugin.yml version **1.0.1** for all three, regardless of the historical remote filename suffix 1.0.5.
- All plugin.yml bytes are identical among #41, #52, #59.
- All **11** packaged SQL migration resources are byte-identical among #41, #52, #59.
- Relative to #52, PR #59 removes **zero** net.enthusia.loreitems classes, adds **two** and modifies **11**. This confirms unchanged packaged class availability; it does not establish full runtime compatibility.
- PR #59 25/25 exact-head workflows passed, including Paper 26.2 / Java 25 acceptance. The new per-pass shulker reuse and emitted scan-coalescing metrics address earlier hotspots. Synthetic scan-pass median/p95 improvement against #52 does **not** establish an actual reduction in live SMP MSPT. Individual synthetic passes remained as long as 100ms, so the 256-node pass cap is not a wall-time guarantee.

**Go/no-go:** candidate JAR integrity and static migration compatibility are checked. Live rollout is **NO GO / NOT STAGED** until the following gates pass:

1. Confirm the **current** active SMP plugin file's real path, size, SHA and that no second LoreItems JAR is eligible for loading. Historical snapshots are not current proof.
2. Preserve exactly **one verified pre-upgrade JAR copy** outside plugin scanning. Take and check **one SQL-consistent WAL-aware SQLite database + supporting data backup**. Copying a live SQLite DB file with SFTP alone is not safe.
3. Use a reviewed and authorized SFTP write transport with task-specific approval; Blackboard #101/#103/#104 remain deployment blockers. Upload as a non-.jar temporary name, verify remote hash, promote safely, verify only one active JAR.
4. **Do not manually restart the SMP.** Allow the normal daily restart; confirm successful startup and identity, tracking, database and duplicate-protection health.
5. Measure matched pre/post Spark MSPT and tracking queue statistics without additional AI inference. After that, coordinate the four-phase AI-off/AI-idle/AI-inference/recovery test under approved risk conditions. The earlier AI-on correlation is not proof that LoreItems alone was responsible.

PR #59 is currently an **unmerged test artifact**. A production release requires canonical review/merge and verification of the final **merged-source** JAR; do not call this test binary a merged release.

Related: LoreItems #39, #54, #59; Enthusia-AI #119 and draft #120; Blackboard #101.
