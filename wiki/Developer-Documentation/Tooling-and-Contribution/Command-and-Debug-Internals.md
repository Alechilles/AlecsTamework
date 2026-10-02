---
title: "Command and Debug Internals"
order: 13
published: true
draft: false
---
# Command and Debug Internals

Parent: [Tooling and Contribution](/mod/alecs-tamework/tooling-and-contribution) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

## Command package
The `commands/` package contains the public `/tw` command surface, with `TameworkCommandRoot` as the root and focused command classes for ownership, alarms, progression, traits, config/settings UI, NPC lookup, diagnostics, and debug toggles.

## Debug state
`Tamework.java` stores the live debug booleans and role filter state for:
- hook
- spawner
- prompt
- despawn
- lag
- coop
- breeding
- needs-consume diagnostics
- needs-damage diagnostics

`TwDebugConfig` supplies asset-backed defaults for those toggles.

Additional diagnostics that are command-driven (not startup-toggle defaults) include:

- `/tw debug view hitboxes`
- `/tw debug view spawn-beacons [radius|off]`
- `/tw debug telemetry crash`
- `/tw debug persistence simulateerror`

`TameworkShowSpawnBeaconsCommand` maintains per-player radius sessions while
`SpawnBeaconVisualizationService` owns one non-persistent visual proxy per
covered natural beacon. Proxies deliberately omit every beacon and gameplay
component, so they cannot enter Hytale's spawning systems.

Tamework 5.0 no longer registers the `status`, `health`, `detail`, `export`,
`reviveready` and `compact` subcommands of `/tw debug persistence`. They served
the SQLite persistence that 5.0 replaced. Only `simulateerror` remains. The
operator command `/tw persistence start-fresh [confirm]` lives in
`TameworkPersistenceCommandGroup`; see
[World Migration for Server Admins](/mod/alecs-tamework/world-migration-for-server-admins).

## Supporting systems
- `CompanionDespawnDiagnosticsSystem`
- `CommandNpcRelocationOnLoadSystem`
- `CommandTeleportArrivalRelocationSystem`
- `NpcDebugDisplayResumeOnLoadSystem`
- `CommandLinkedRevivableDropSuppressionSystem`

## Maintenance advice
- Keep runtime toggles, persisted defaults, and command handlers aligned
- If a new debug channel is added, wire it through the plugin state, command layer, and `TwDebugConfig`

## Related Pages
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command Runtime and Linked Panel Internals](/mod/alecs-tamework/command-runtime-and-linked-panel-internals)



