# Agent Guardrails

Use the checks relevant to the changed behavior. Keep policy in `AGENTS.md`; keep routing and executable commands here. Run commands from the Tamework repo unless a different directory is stated.

## Before Editing

- Confirm the requested target is the source repo, runtime copy, packaged artifact, save override, or external wiki path the user named.
- Read the closest existing docs and tests before introducing a new pattern.
- Check existing base-game assets and Tamework mechanisms, then use the simplest reliable approach. Source evidence can rule out an approach without an implementation attempt.
- Extract responsibilities when this makes the requested change clearer or safer. Class size alone does not require a refactor.

## Runtime and ECS Safety

Before finalizing changes to runtime systems, tick paths, async callbacks, damage dispatch, or player access, run:

```bash
rg -n -F -e 'PlayerRef.getComponent(Player' -e '.getHolder().getComponent(Player.getComponentType())' src/main/java
bash ../gradlew -p .. :alecstamework:test \
  --tests '*EcsWriteSafetyGuardTest' \
  --tests '*AsyncThreadSafetyGuardTest'
```

The search highlights the direct lookup forms prohibited by the async guard; no matches is a normal `rg` exit code of 1. Inspect matches and the changed execution path. Resolve players from the owning world/store or active query callback, and use `CommandBuffer` for system writes. These static checks do not replace checking thread ownership. Add focused behavior tests when the Test Value Gate is met; a full suite is needed when shared behavior or several subsystems are affected.

For new or materially expanded periodic scans or reconciliation, record:

- which reliable events or dirty signals were considered;
- why continuous simulation, time-based behavior, recovery, or missing events
  still require a tick;
- the query scope, cadence, maximum batch, and idle backoff;
- whether a low-frequency reconciliation pass can replace frequent polling;
- unload and shutdown cleanup for listeners, caches, and scheduled work.

Reject full-player or full-entity polling for infrequent state changes when a
reliable event source exists.

Normal per-entity simulation only needs notes for non-obvious cadence and ownership. Keep bounded I/O and pure computation on immutable data separate from live world/store access.

## Agent Documentation Checks

Run this after changing agent guidance, navigation links, or these tooling scripts:

```bash
pwsh -NoProfile -ExecutionPolicy Bypass \
  -File scripts/tools/check-agent-docs.ps1
```

The check verifies that:

- required navigation docs and referenced safety guard tests exist,
- relative Markdown links in agent docs and the local `AGENTS.md`, when present, resolve.

The checker does not require exact policy phrases, external lesson directories, or a fresh index. It does not verify policy correctness. Refresh the navigation snapshot after layout changes that affect it with `scripts/tools/build-agent-index.ps1`. To check that snapshot explicitly, add `-CheckGeneratedIndex` to the checker command. Ordinary behavior changes do not require index regeneration.

## Companion Persistence

The companion index and its file store are described in
[ADR 0011](../decisions/0011-companion-index-persistence.md). After changing
`companion/index`, `companion/store`, `companion/live`, `companion/flow`, or
`companion/migrate`, run the focused checks for the changed area:

```bash
bash ../gradlew -p .. :alecstamework:test \
  --tests '*CompanionIndexTest' \
  --tests '*CompanionStorageTest' \
  --tests '*CompanionStoreTest' \
  --tests '*CompanionWriterTest' \
  --tests '*CompanionRecordBsonTest' \
  --tests '*CompanionFenceTest' \
  --tests '*RestoreFlowTest' \
  --tests '*CompanionImporterTest'
```

`CompanionIndexTest` covers revisions, generations, counts, and the index lock.
`CompanionStoreTest` covers owner and snapshot files and unreadable files.
`CompanionWriterTest` covers write-behind flushes and their order.
`CompanionStorageTest` covers startup readiness and old-save detection.
`CompanionFenceTest` covers the generation fence that removes stale bodies.
`RestoreFlowTest` covers respawn from a saved snapshot. `CompanionImporterTest`,
`LegacyReaderTest`, and `LegacyMapperTest` cover the 3.x and 4.x import. After
changing the bundled SQLite driver or `shadowJar` exclusions, run
`bash ../gradlew -p .. :alecstamework:packagingTest`; `SqlitePackagingIT` opens
a database through the packaged driver the importer uses.

Hold these rules: commit the index before any live effect; raise the
generation on every holder change; do no file I/O or snapshot decoding on the
world thread; keep world-time signs; and add no second persistence authority.

## Artifact Freshness Checks

When behavior differs between source and game:

- Use `docs/agents/runtime-vs-source-checklist.md` to identify the loaded copy. Compare framework assets in `src/main/resources` or optional examples in `examples/asset-pack` with the linked server's `Modding/run/mods` first. Check `UserData\Mods` only for a legacy/manual runtime.
- Inspect packaged jar/zip contents instead of assuming a build copied the latest files.
- Check save overrides before changing source assets. A stale full-array override can mask correct source behavior.
- Run `/patchwork status` when an asset patch appears valid but does not win at runtime. Confirm the elected runtime, eligible neutral/legacy roots, generated-pack location, and whether the target is restart-required.

## Release Checks

Use `alec-mod-release-prep`, `docs/Build-and-Packaging.md`, and then `alec-mod-publish` for release work. Follow the current local `mod-release-publisher` workflow for credentials and uploads. Resolve the requested version and destinations rather than assuming defaults. Packaging checks include packaged behavior, manifests, and affected assets.

