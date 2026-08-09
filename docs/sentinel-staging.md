# Enthusia Sentinel staging

EnthusiaLoreItems uses the existing Enthusia Sentinel service controlled by `wsg138/EnthusiaStaff-Staging`. Do not create another Sentinel instance or another GitHub App.

## Primary command

Post this exact comment on an open, non-draft same-repository pull request when the exact current head has a successful Sentinel artifact:

```text
@enthusia-sentinel test restart
```

Before assuming any other profile exists or is executable, read the current `wsg138/EnthusiaStaff-Staging/docs/sentinel-commands.md` and this repository's current `.enthusia-test.yml`.

## When to use restart

Use restart validation after the intended PR head:

- builds successfully;
- has the exact `enthusialoreitems-plugin` GitHub Actions artifact for that SHA;
- contains a valid `.enthusia-test.yml`;
- is ready for Paper lifecycle/restart validation.

The PR must be same-repository, open, non-draft, and requested by a Sentinel-authorized user. Sentinel revalidates the exact PR head before admission and artifact acquisition.

## Artifact contract

The existing `CI` workflow builds the deployable shaded Paper plugin and publishes a dedicated successful-run artifact:

- artifact name: `enthusialoreitems-plugin`
- JAR entry: `build/libs/EnthusiaLoreItems.jar`

Sentinel resolves only a successful GitHub Actions workflow for the exact requested commit SHA. Do not substitute release downloads, branch-latest artifacts, arbitrary URLs, or external file hosts.

## What success means

`PAPER_RESTART_OK` means Sentinel successfully:

1. resolved the exact PR SHA and manifest;
2. obtained and verified the exact successful-workflow artifact;
3. started disposable Paper with the LoreItems JAR;
4. reached readiness;
5. shut down and fully reaped the first Paper process;
6. started Paper a second time using the same disposable state;
7. passed Sentinel's restart/persistence checks;
8. shut down and cleaned up the disposable runtime.

A queued, rejected, cancelled, stale-SHA, missing-artifact, cleanup-failed, or otherwise failed job is not a pass.

## Evidence future workers must record

For every Sentinel run used as acceptance evidence, record:

- repository and PR number;
- exact tested SHA;
- command comment ID/link when available;
- Sentinel job ID;
- workflow run/artifact identity when reported;
- terminal result code;
- Sentinel result comment ID/link;
- cleanup state when relevant.

Never claim live Sentinel validation without direct production Sentinel evidence.

## Current manifest scope

Initial onboarding intentionally enables only:

- `startup`
- `restart`

LoreItems has additional SQLite/configuration behavior, but broader Sentinel profiles should be added only after their exact fixtures/assertions are deliberately designed and confirmed against the current production Sentinel contract.
