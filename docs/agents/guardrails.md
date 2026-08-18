# Agent Guardrails

This page collects repo-specific checks that should be run before trusting an answer or change. Keep policy in `AGENTS.md`; keep routing and executable commands here.

## Before Editing

- Confirm the requested target is the source repo, runtime copy, packaged artifact, save override, or external wiki path the user named.
- Read the closest existing docs and tests before introducing a new pattern.
- Prefer base-game assets and existing Tamework config before adding Java behavior.
- If a change touches an existing class over 1000 lines, look for an extraction opportunity instead of adding more behavior to the same class.

## Runtime and ECS Safety

Before finalizing changes to runtime systems, tick paths, async callbacks, damage dispatch, or player access, run:

```bash
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
bash ../gradlew -p .. :alecstamework:test \
  --tests '*EcsWriteSafetyGuardTest' \
  --tests '*AsyncThreadSafetyGuardTest'
```

If matches are in tick/runtime paths, resolve players from the current world/store or route writes through `CommandBuffer` before merge.

Before adding or expanding a tick system, record:

- which reliable events or dirty signals were considered;
- why continuous simulation, time-based behavior, recovery, or missing events
  still require a tick;
- the query scope, cadence, maximum batch, and idle backoff;
- whether a low-frequency reconciliation pass can replace frequent polling;
- unload and shutdown cleanup for listeners, caches, and scheduled work.

Reject full-player or full-entity polling for infrequent state changes when a
reliable event source exists.

## Agent Documentation Checks

Run this after changing `AGENTS.md`, `docs/agents`, package layout, scripts, tests, or major docs:

```bash
pwsh -NoProfile -ExecutionPolicy Bypass \
  -File scripts/tools/check-agent-docs.ps1
```

The check verifies that:

- required agent docs exist,
- `AGENTS.md` still links to the agent routing docs,
- the generated agent index is current,
- the safety guard tests still exist,
- the external Lessons Learned path is reachable.

## Replacement Persistence Architecture

After changing the replacement kernel, identity, lifecycle, snapshot, operation, recovery, or
projection code, run:

```bash
bash ../gradlew -p .. :alecstamework:test \
  --tests '*ReplacementPersistenceArchitectureGuardTest' \
  --tests '*PersistenceProcessCrashMatrixTest'
```

The architecture guard enforces one canonical lifecycle mutation path, connection-bound stores,
transaction callback isolation, no dependency on the superseded SQLite package, no premature
outbox compaction, and a 500-line replacement-core class ceiling. The forked-process matrix
verifies recovery from each shared prepare, live-apply, durable, publication, compensation, and
shutdown crash boundary.

## Artifact Freshness Checks

When behavior differs between source and game:

- Compare source assets under `src/main/resources` against runtime assets in `UserData\Mods`.
- Inspect packaged jar/zip contents instead of assuming a build copied the latest files.
- Check save overrides before changing source assets. A stale full-array override can mask correct source behavior.
- Run `/patchwork status` when an asset patch appears valid but does not win at runtime. Confirm the elected runtime, eligible neutral/legacy roots, generated-pack location, and whether the target is restart-required.

## Release Checks

Use release skills and scripts for release work. The common entry points are:

```powershell
.\scripts\release\validate-release.ps1 -Version <version>
.\scripts\release\build-package.ps1 -Version <version>
.\scripts\release\publish-prebuilt.ps1 -Version <version>
```

Pass the explicit version from the manifest/build source rather than assuming a default.

