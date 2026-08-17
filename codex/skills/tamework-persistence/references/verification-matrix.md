# Persistence Verification Matrix

Name the production regression before adding a test. A useful test exercises
production behavior and observes a result, durable state, effect, event,
authorization decision, recovery outcome, or player-visible output.

## Select Evidence by Risk

| Change | Minimum focused evidence |
| --- | --- |
| Domain transition, codec, planner, or mapper | Focused unit test for the returned state, decoded result, or mapped event |
| SQLite store or transaction participant | Real temporary SQLite test for committed rows, revision fences, rollback, and failure semantics |
| Replacement database-only operation | Operation test for acceptance, atomic durable state, outbox event, and idempotent replay |
| Replacement live-effect operation | Focused process-crash test across prepare, live apply, unknown commit, durable commit, publication, and compensation boundaries that the feature uses |
| Projection consumer or public semantic event | Outbox replay and checkpoint test with duplicate delivery |
| Recovery, incident, quarantine, or circuit | Restart/recovery test that proves containment and unrelated-work progress |
| Replacement schema or public import | Representative fixture through classifier, importer, verifier, atomic publication, and refusal paths |
| Bonded store, lease, capture, revive, cleanup, or projection | Focused bonded behavior test with stable profile identity, exact lease/evidence, restart behavior, and no duplicate charge or projection |
| Runtime system or deferred world work | Focused behavior test plus ECS and async safety guards |
| Settings or data-path store | Focused filesystem behavior test for the caller-visible read/write/recovery result |

Do not add tests that grep source, pin private structure, count incidental files
or JSON keys, or prove that an implementation symbol exists. Existing narrow
architecture guards can protect documented failure invariants that runtime
tests cannot exercise reliably.

## Commands

Run focused tests first:

```bash
bash ../gradlew -p .. :alecstamework:test --tests 'fully.qualified.TestClass'
```

For replacement core, operation, recovery, or projection changes, include:

```bash
bash ../gradlew -p .. :alecstamework:test \
  --tests '*ReplacementPersistenceArchitectureGuardTest' \
  --tests '*PersistenceProcessCrashMatrixTest'
```

Run feature-specific crash tests when the operation has a live effect. The
generic crash matrix does not replace feature evidence.

For runtime, tick, player access, or ECS changes, run:

```bash
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
bash ../gradlew -p .. :alecstamework:test \
  --tests '*EcsWriteSafetyGuardTest' \
  --tests '*AsyncThreadSafetyGuardTest'
```

After Java changes, run the complete project suite:

```bash
bash ../gradlew -p .. :alecstamework:test
```

After agent docs, package layout, scripts, tests, or major docs change, run the
repository agent-doc checks from Git Bash:

```bash
pwsh -NoProfile -ExecutionPolicy Bypass \
  -File scripts/tools/check-agent-docs.ps1
```

Use `bash ../gradlew -p .. stageAllModAssets` only when staged runtime evidence
is needed. Launch `runAllMods` only for an explicit live-server acceptance task.
