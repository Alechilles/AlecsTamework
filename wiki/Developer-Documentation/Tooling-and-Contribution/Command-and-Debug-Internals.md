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

- `/tw showhitboxes`
- `/tw debugcrashtelemetry`
- `/tw debugdb [status|health|integrity|detail]`

`TameworkDebugDbCommand` reads bounded status, metrics, and detail snapshots
from the replacement `PersistenceBootstrap`. Its actions are observational:
they do not repair data, retry operations, clear incidents, or change circuits.

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



