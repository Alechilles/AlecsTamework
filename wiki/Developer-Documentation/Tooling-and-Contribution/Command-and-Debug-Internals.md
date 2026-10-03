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

`TameworkShowSpawnBeaconsCommand` maintains per-player radius sessions while
`SpawnBeaconVisualizationService` owns one non-persistent visual proxy per
covered natural beacon. Proxies deliberately omit every beacon and gameplay
component, so they cannot enter Hytale's spawning systems.

Tamework 5.0 removed `/tw debug persistence` and all its subcommands
(`status`, `health`, `detail`, `export`, `reviveready`, `compact` and
`simulateerror`). They served the SQLite persistence that 5.0 replaced. Store
health is in the server log and the diagnostics API; see
[Companion Store and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths).

Admin persistence commands that remain:

- `/tw persistence start-fresh [confirm]` (`TameworkPersistenceCommandGroup`):
  available only while old saves block the world; see
  [World Migration for Server Admins](/mod/alecs-tamework/world-migration-for-server-admins).
- `/tw companions forget|restore <self|player|UUID> <companion name or id> [confirm]`
  (`TameworkCompanionsCommandGroup`): resolves a player's companion that is held
  by a capture item Tamework cannot reach, a coop, or is lost. Without `confirm`
  each command only previews. `forget` turns an `ITEM` companion into a released
  tombstone and frees the owner's slot; it works from the console. `restore`
  restores an `ITEM`, `COOP` or `LOST` companion next to the admin, so it must be
  run in game. Both raise the generation, so any surviving copy of the item or
  coop entry stops working. Bonded companions are refused.
- `/tw bonded grant` (`TameworkBondedCommandGroup`): gives a player a stored
  bonded companion.

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
- [Companion Store and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command Runtime and Linked Panel Internals](/mod/alecs-tamework/command-runtime-and-linked-panel-internals)



