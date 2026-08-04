# Persistence Replacement Release Checklist

> **Live acceptance pending:** The replacement core and required persistence
> feature recovery under ADR 0008 are implemented and automated gates are
> green. Public release preparation remains blocked until the exact artifact
> recorded below passes the fresh-world, copied-save, cross-world, restart, and
> rollback live matrices.

Use this checklist for the first public release of the replacement persistence
lineage. It deliberately reuses the normal Maven and release-preparation
workflows. There is no separate persistence candidate builder or evidence
schema to maintain.

## Compatibility boundary

- Public Tamework `v2.16.1`, released June 30, is the upgrade boundary.
- Public schema v2, v3, and v4 sources import into fresh
  `tamework-state.sqlite` schema v1.
- The original `tamework.sqlite` source and its sidecars remain unchanged.
- Unreleased v5-v9 sources are refused without creating or changing a
  replacement target. Testers restore a public backup or create a new world.
- Tamework never creates or restores a complete Hytale world backup.

## Candidate identity

Record these values before live testing:

| Evidence | Value |
| --- | --- |
| Tamework commit | `fa4191a88c45638ff54e1b9dc220c0ccf924fdfa` |
| Tamework version | `3.0.0` |
| Hytale version | `0.5.7` |
| Candidate artifact path | `target/Alec's Tamework! v3.0.0.jar` (test candidate; evidence copy retained with the migration baseline) |
| Candidate SHA-256 | `ccc7b5f25f0ab0bdc06d5fb2dc915ea096a4103ae32f97fe9fcb5061b81e7a24` |
| Maven test result | Clean run on 2026-07-25: 3,001 tests, 0 failures, 0 errors, 1 intentional environment-dependent skip |
| Test-candidate package result | Ordinary Maven package passed; artifact size 24,376,018 bytes. Release packaging remains deferred until live acceptance completes. |

The worktree must be clean, and every live boot must use the artifact with the
recorded SHA-256.

This records the latest automated candidate only. The unchecked manual
fresh-world, copied-save, and rollback gates remain required before publishing.

Earlier candidates are superseded:

- `5930cf187cca52a2e0ab00e79a2d4c402bf3f7cc4cb77d487d3a0c20f72abd81`
  passed the imported capture/release, coop, death/Lost, ordinary cross-world,
  Hub, and missing-imported-companion recovery lanes. Final migration-world
  checks exposed two narrower defects: generic public role labels such as
  `Wolf_Black` were treated as custom names, and a physically live current
  alias could be refused capture while its canonical lifecycle still said
  Unloaded or named another world. Commit `cabe6df2` centralizes role-identity
  name suppression and allows only that already-current alias to reconcile its
  stale location through the canonical profile operation before capture.
  Historical aliases and all other identity conflicts remain fail-closed.
- `fe394a166bbc66a3e3bee0b25cdec7927663a799faebc2c14dedc5e20ab6df05`
  passed imported capture release, coop, death, lost, and ordinary cross-world
  recall testing, but one genuinely absent imported companion remained
  Unloaded after Recall. Commit `64a910a3` now retains only one valid
  released-coop history payload as single-use migration evidence. It converts
  that evidence to ordinary Lost restoration data only after an explicit
  Recall exhausts without physically moving an NPC; all current alias, owner,
  role, metadata, lifecycle, source revision, and payload-hash fences are
  rechecked atomically.
- `c81cd8b58b293d07b893d6cf8cae2cd45b1d83019ed773d18b9793f0c4ac1d64`
  resolved public filled spawners to their exact imported capture profiles, but
  rejected the released `CaptureClearsOwner` shape: the item intentionally
  omitted `OwnerUuid`, the canonical profile retained the current owner, and
  release produced a redundant same-owner assignment. Commit `2d1b120f`
  normalizes only that equal assignment into preserve-owner evidence while a
  genuinely different owner remains fail-closed.
- `35e09a60812271802aa609640d8c81c5ca4096fd996a98024ad1073dd891a005`
  imported the copied public save correctly but silently rejected filled
  spawners created by public `v2.16.1`. Commit `12ba60e9` now joins the exact
  public item evidence to its imported canonical capture profile through the
  normal receipt-first release operation.
- `6faf1eedaf303f17337c6e1abdf637c1755fd61ee8dffaeb8f2b2767a1b5a653`
  passed the focused Hub rehearsal but falsely quarantined public coop
  residents during the first copied-save test. The source remained unchanged.
  Commit `2cf3f015` now treats only housed v2.16.1 rows as current occupancy,
  retains released rows as inactive history, and matches the exact released
  profile-state key.
- `246b580a0df3f6f148a9fbac8d5fa82148845010af82889f50e6d26cfe17ef92`
  failed owner-assigned spawner release in the disposable `TW Persistence
  Refresh` world on Hytale `0.5.7`. Commit `f71291e0` corrected the
  capture-release scope policy and added production control-plane regression
  coverage.
- `94460d4a470a3a4e3d88a79f0d00f0bec63d453f96329f5856f0902d7abf8293`
  then reached the actor-receipt durability barrier but failed because
  whole-player cloning encountered a Hytale component with no direct codec.
  Commit `f3183c67` retained the receipt-first recovery sequence while matching
  Hytale's shallow world-thread save-holder construction. The two interrupted
  releases remain exact `RETRYABLE` operations with captured canonical
  profiles.

Do not use any superseded candidate for further release evidence.

## Automated gates

Run the ordinary project and release gates:

```bash
./gradlew test
powershell -NoProfile -File scripts/release/validate-release.ps1 -Version <version>
powershell -NoProfile -File scripts/release/build-package.ps1 -Version <version>
```

Do not pass `-SkipTests` when creating the release artifact. The build-package
workflow performs a clean test and package of the exact candidate.

The full Maven run must include the following evidence:

- Immutable fixtures, public v2-v4 import, and v5-v9 refusal:
  `LegacyPersistenceFixtureTest`, `LegacySourceClassifierTest`,
  `PublicPersistenceImporterTest`, `PublicPersistenceTargetOpenerTest`, and
  `PublicPersistenceRuntimeImportTest`.
- Released v4 logical behavior: `PublicV4ProfileReadParityTest`,
  `PublicV4DormantRestorationTest`, `PublicV4CoopReleaseTest`, and
  `ReplacementNpcProfilesApiTest`.
- Shared crash recovery: `PersistenceProcessCrashMatrixTest` and the feature
  process-crash tests.
- Startup and shutdown: `PublicPersistenceRuntimeTest`,
  `PublicPersistenceStartupFailureMatrixTest`, and
  `PublicPersistenceShutdownTest`.
- Architecture and fault seams: `ReplacementPersistenceArchitectureGuardTest`,
  `PersistenceFaultInjectionArchitectureTest`,
  `PersistenceConsolidationInvariantManifestTest`, and
  `PersistenceConsolidationInventoryGuardTest`.
- ECS and thread safety: `EcsWriteSafetyGuardTest` and
  `AsyncThreadSafetyGuardTest`.
- Replacement runtime budgets: `ReplacementPersistencePerformanceGateTest`.

A successful full Maven result is the authority. Do not introduce a second
script that reparses Surefire reports or duplicates Maven's pass/fail decision.

## Manual live smoke: fresh world

Use a disposable fresh world with the exact candidate artifact.

- [ ] Start cleanly, tame a companion, and verify its identity and name.
- [ ] Capture the companion into a spawner and release it.
- [ ] Capture a live companion into a loaded coop and release it.
- [ ] Admit an eligible filled capture item directly into a supported managed
      coop, then release the same canonical companion.
- [ ] Configure global and per-world owner limits plus one population group.
      Verify `MaxOwned` counts active, unloaded, captured, cooped,
      roster-stored, provisioned-dormant, dead, and Lost profiles; verify
      `MaxActive` reserves and releases active slots without oversubscription.
- [ ] Open a Dragon Horn-style owner/command-family roster. Store and summon
      one companion, dismiss it, allow one timed summon to expire, log out
      during another summon, and verify the configured cooldown across restart.
- [ ] Provision one Soul Bond Miniwyvern, activate it, and verify repeated
      provisioning requests remain idempotent. Exercise its Dead/Lost paid
      revival path and confirm a duplicate live identity is never created.
- [ ] Exercise both capture source-consumption modes: a terminal denial and a
      success under `ResolvedAttempt`, plus one ordinary `SuccessOnly` denial.
      Verify exact source spending and no spending for retryable/unresolved
      outcomes.
- [ ] Complete one `TameAndCommandLink` capture and verify ownership plus
      command-family roster membership publish together.
- [ ] Quote, reject, and then confirm a paid Dead/Lost roster revival. Verify
      the exact recipe quantities, no cost on denial, and one restored profile.
- [ ] Exercise death restoration and lost-companion restoration.
- [ ] Stop cleanly, boot the same world twice, and repeat one capture/release.
- [ ] Verify no companion is duplicated, lost, unexpectedly teleported, or
      restored into the wrong world.
- [ ] Verify startup and shutdown logs contain no unresolved persistence
      failure.

Record:

| Evidence | Value |
| --- | --- |
| World fixture/reference | |
| Candidate SHA-256 at each boot | |
| Boot count | |
| Test notes or log archive | |
| Result | |

## Manual live smoke: copied v2.16.1 save

Use a disposable copy of a real public `v2.16.1` save. Create and verify an
external whole-save backup before installing the candidate.

- [ ] Record SHA-256 values for `tamework.sqlite` and any existing `-wal` or
      `-shm` sidecars before the first candidate boot.
- [ ] Boot the exact candidate and confirm the replacement imports into
      `tamework-state.sqlite`.
- [ ] Verify representative active, captured, dead, lost, and coop profiles.
- [ ] Release at least two filled spawners that were captured by public
      `v2.16.1`, then perform one new capture/release, one restoration, and one
      coop release.
- [ ] Stop cleanly and boot the imported save a second time.
- [ ] Confirm the public source files still have their original hashes and no
      source file was moved, renamed, or deleted.
- [ ] Verify no companion is duplicated, lost, unexpectedly teleported, or
      restored into the wrong world.

Record:

| Evidence | Value |
| --- | --- |
| Opaque backup/reference | |
| Public source hashes before | |
| Public source hashes after | |
| Candidate SHA-256 at each boot | |
| Test notes or log archive | |
| Result | |

## Release decision

Release only when:

- the normal full test, validation, and package workflows pass;
- the artifact commit and SHA-256 are recorded;
- both live-smoke lanes pass with the exact artifact;
- the copied public source remains byte-for-byte unchanged; and
- every observed duplication, disappearance, wrong-world restoration, startup
  failure, or shutdown failure is resolved.

If a live rehearsal fails, stop the server and preserve the replacement
database and logs for diagnosis. Testers may restore their external public
backup or start a new world. After replacement gameplay has occurred, returning
to `v2.16.1` requires restoring the matching pre-upgrade whole-save backup; an
old JAR alone is not a safe rollback.
