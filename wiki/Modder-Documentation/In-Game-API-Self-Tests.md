---
title: "In-Game API Self-Tests"
order: 4
published: true
draft: false
---
# In-Game API Self-Tests

Parent: [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

Tamework includes an operator-facing self-test harness for the experimental integration API. It provisions its own bundled fixtures, runs live contract checks against `TameworkApi`, and tears the fixtures back down when you are done.

## Command flow
Use the commands in this order:

```text
/tw api test prepare
/tw api test run all
/tw api test reset
```

Available commands:
- `/tw api test prepare`
- `/tw api test status`
- `/tw api test run [core|profile|command-links|configs|policies|diagnostics|all] [verbose]`
- `/tw api test reset`

These commands are intended for trusted operators and use the `tamework.api.test` permission node with the usual OP/Admin/Operator fallback groups.

## What `prepare` creates
`prepare` provisions a deterministic fixture set for the invoking player in the current world:
- one owned linked example NPC
- one stranger-owned linked example NPC
- one real bundled example command whistle in the player hotbar

The fixtures use Tamework’s bundled example content:
- role id: `Mob_Tamework_Example`
- command item: `Tamework_Command_Whistle_Example`

Each fixture is marked with an internal self-test component, linked to the generated whistle tool id, and written through the normal profile-persistence queue.

## What `run` validates
`run core` checks API availability, versioning, capabilities, global config reads, and persistence diagnostics.

`run profile` checks:
- profile id resolution
- profile reads by NPC UUID and profile id
- role/tamed/tool-id fields

`run command-links` checks:
- command-link reads by NPC UUID
- linked tool ids
- saved home-position reads

`run configs` checks:
- interaction config resolution
- companion config resolution
- happiness config resolution
- needs config resolution
- breeding config resolution
- trait config resolution

`run policies` checks:
- ownership reads
- owner positive/negative checks
- claim/damage decision coherence
- population-cap reads

`run diagnostics` checks:
- persistence diagnostics availability
- health snapshot presence
- queue-metric readability

Add `verbose` to print each individual assertion instead of only the suite summaries.

## Reset behavior
`reset` removes:
- live self-test NPC fixtures in the current world
- the generated self-test whistle from the player hotbar
- persisted profile rows created for the fixture NPC UUIDs

If you want to repro a clean setup, always run `reset` before `prepare` again.

## Notes
- `run` is read-only. Only `prepare` and `reset` mutate state.
- The suite is intentionally narrow and is meant to validate the public API contract, not replace the Maven/JUnit test suite.
- The fixture content is bundled with Tamework, so the self-tests do not depend on downstream mods like Animal Husbandry.
