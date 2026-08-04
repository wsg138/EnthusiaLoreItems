# PR #6 durable template revision rollout core

Date: 2026-08-04 (America/Indiana/Indianapolis)

## Active phase and logical item

- Phase: Implementation PR 4 — Editing and destructive administration.
- Exact logical item: durable immutable template-revision creation and bounded, restart-safe rollout planning for known active instances.
- Repository: `wsg138/EnthusiaLoreItems`.
- Pull request: #6 — `PR 4a: Add durable template revision rollout core`.
- Branch: `agent/loreitems-pr4-revision-rollout-core`.
- Starting `main`: `ea6f9548c0a7aea1ec82ee88d5329a7da49c4c1f`.
- Verified implementation head: `1a82a3a2af339aca362fa9318320c54040c7c92d`.

## Live-state reconciliation

`handoffs/CURRENT.md` and report 0028 recorded PR #5 as awaiting merge finalization. Live GitHub showed that PR #5 had already merged into `main` as `ea6f9548c0a7aea1ec82ee88d5329a7da49c4c1f`, and no relevant pull request remained open. Live GitHub and code therefore took priority, the stale finalization instructions were not repeated, and the first independently safe Implementation PR 4 subphase was started from the merged `main` state.

No local GitHub checkout or functional `gh` network path was available in this agent environment. The implementation was published through GitHub Git Data operations as three coherent commits rather than a chain of one-file contents-API commits.

## Delivered scope

This pull request delivers the durable template-revision rollout planning core:

- application contracts and validated results for starting a revision, scheduling another bounded rollout batch, and discovering incomplete rollout planning;
- optimistic revision fencing so an edit can append only the immediate next immutable `LoreDefinitionRevision`;
- one SQLite transaction that advances the definition revision, inserts the immutable template revision, advances the initial bounded instance batch, creates durable mutation intents, and appends the audit event;
- bounded continuation that advances active instances' `desired_revision` and creates one `TEMPLATE_UPDATE` mutation per instance and target revision;
- restart discovery that repeatedly polls the first bounded incomplete-rollout page, avoiding offset skips as the result set shrinks;
- database-enforced idempotency through a partial unique index on template-update instance/revision intent;
- serialization of definition edits while prior physical template-update work remains nonterminal, preventing a newer edit from overtaking and later being reverted by stale physical work;
- stale, missing, and deleted definition rejection for continuation work;
- a forward-only V2 migration with rollout lookup and mutation queue indexes;
- 64-bit pending-mutation revision persistence consistent with `TemplateRevision`.

The capability is intentionally not wired into Paper commands or workers in this pull request. It is inert until a later subphase supplies the bounded owning-thread physical mutation adapter.

## Remaining Implementation PR 4 requirements

This subphase intentionally leaves the following for later, separately reviewable work:

- the bounded Paper `TEMPLATE_UPDATE` executor and update-on-natural-encounter path;
- identity/fingerprint verification and physical replacement while preserving the hidden instance UUID;
- pause, resume, and review controls for queued updates;
- inventory GUI and chat-backed field editing;
- names, colors, gradients, lore, enchantments, hidden enchantments, glint, damage, unbreakable state, attributes, models, and common component editing;
- replace-template-from-held-item confirmation flow;
- exact-instance removal, purge, full deletion, deleted-ID handling, and queued physical removal;
- Paper command and permission wiring for these later operations.

## Harsh-review findings and fixes

A separate full-pull-request review found and fixed these defects before finalization:

1. A second definition edit could overtake unfinished physical update work and allow stale queued work to restore an older template. Revision start now rejects while any prior active instance remains behind or any prior `TEMPLATE_UPDATE` mutation is nonterminal.
2. Application checks alone could not guarantee one update intent per instance/revision after restart or retry. V2 now adds a partial unique SQLite index enforcing that invariant.
3. Migration tests covered clean schema creation but not forward upgrade from the prior released V1 state. An explicit V1-to-V2 upgrade regression was added.
4. Offset pagination over the shrinking incomplete-rollout set could skip definitions. Recovery listing now accepts only offset zero and callers repeatedly poll the first bounded page.
5. Pending mutation persistence used a 32-bit revision representation despite the domain using a 64-bit value. The record and SQLite adapter now use `Long`, with a regression above `Integer.MAX_VALUE`.
6. The first exact-head Codacy run reported six maintainability/test findings. Validation complexity was split into focused helpers, result construction was moved out of loops, and the restart orchestration test gained an explicit final assertion.

No confirmed item-loss, duplicate-creation, main-thread blocking, unbounded-work, reload/shutdown, architecture, or phase-boundary defect remained at the verified implementation head.

## Tests and verification

Focused coverage added or extended includes:

- application construction of the next immutable revision and audit intent;
- continuation limit validation and rejection of offset recovery paging before storage access;
- bounded initial rollout planning and subsequent continuation;
- restart discovery and completion of unfinished planning;
- idempotent retry after planning completion;
- rollback of definition revision, instance targets, mutation rows, and audit event when batch insertion fails;
- database rejection of duplicate template-update instance/revision intent;
- rejection of a new revision while prior physical update work remains nonterminal;
- direct storage-boundary rejection of offset recovery paging;
- fresh schema creation, migration rerun, and explicit V1-to-V2 forward upgrade;
- desired-revision round trip above the 32-bit integer range.

Local pre-publication evidence was limited to Java 21 `javac -Xlint:all -Werror` compilation of the new production contracts/adapters against dependency-compatible stubs, focused test compilation against JUnit-compatible stubs, and direct SQLite smoke execution of the V2 indexes, discovery queries, and uniqueness behavior. The permanent GitHub workflow is the authoritative full-project evidence.

Exact-head GitHub Actions evidence for `1a82a3a2af339aca362fa9318320c54040c7c92d`:

- workflow run: `30906709180`;
- job: `91983374282`;
- tested pull-request merge ref: `3025c4b4b92b6a521ad0a6a6a3adac170108a981`, containing head `1a82a3a2af339aca362fa9318320c54040c7c92d` over base `ea6f9548c0a7aea1ec82ee88d5329a7da49c4c1f`;
- Java: Temurin 21.0.11+10;
- Gradle: 8.14.3;
- `gradle --no-daemon clean check`: `BUILD SUCCESSFUL in 1m 10s`; 40 actionable tasks, 28 executed, 4 from cache, and 8 up-to-date;
- repository tooling completed successfully;
- new-code complexity: `No new Codacy-Lizard threshold violations.`;
- exact-head Codacy: `Codacy Static Code Analysis passed on exact head 1a82a3a2af339aca362fa9318320c54040c7c92d.`;
- Codacy PR summary: zero new issues.

No live Paper/Leaf server, production deployment, production database, or production system was tested or accessed.

## Review state at report creation

- PR #6 was marked ready for review.
- One substantive CodeRabbit review was requested after the implementation stabilized.
- CodeRabbit run `cbc3b857-b163-457f-9606-03c2d5459225` was reviewing all 19 implementation files from `ea6f9548c0a7aea1ec82ee88d5329a7da49c4c1f` through `1a82a3a2af339aca362fa9318320c54040c7c92d` when this immutable report was created.
- No submitted review requested changes and no unresolved review thread existed at report creation.
- The documentation head containing this report still requires the permanent exact-head Actions and Codacy checks before merge.

## Preserved boundaries

- No deployment, production access, live database access, or live-server claim.
- No direct push to `main`, rebase, squash, force-push, automatic merge, or released migration edit.
- No temporary diagnostic, evidence-gathering, self-deleting, branch-mutating, or PR-finalization workflow was committed.
- No Paper object is accessed off-thread, because this subphase adds no Paper mutation worker.
- No chunk is force-loaded, no global scan is introduced, and every rollout page/batch is bounded.
- No physical item is edited, removed, deleted, or repaired by this pull request.
- No GUI/chat editor, destructive administration, campaign execution, public API change, deployment, or EnthusiaTags integration was introduced.

## Exact next work

For PR #6 only:

1. verify the handoff-documentation head with the permanent GitHub Actions workflow and exact-head Codacy check;
2. update the PR body with the final head, exact delivered subphase, remaining PR 4 requirements, independent merge safety, tests, harsh-review fixes, and exact verification evidence;
3. allow the single substantive CodeRabbit review to finish and resolve every legitimate in-scope finding, if any;
4. confirm no requested-change review or unresolved thread remains, `main` has not advanced incompatibly, and the pull request remains mergeable;
5. merge PR #6 with a normal merge commit, verify the resulting `main`, attempt normal branch cleanup when supported, and stop.

After PR #6 merges, the exact next logical item for a later chat is Implementation PR 4b: implement the bounded Paper `TEMPLATE_UPDATE` mutation executor and natural-encounter update integration, preserving the hidden instance UUID and definition ID, verifying old identity/fingerprint before replacement, and transitioning ambiguous work to `REVIEW_REQUIRED` rather than overwriting it.
