---
title: "In-Game API Self-Tests"
order: 4
published: true
draft: false
---
# In-Game API Self-Tests

Parent: [Testing and Diagnostics](/mod/alecs-tamework/testing-and-diagnostics) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Tamework includes an operator-facing self-test harness for the public integration API contract. It provisions its own bundled fixtures, runs live contract checks against `TameworkApi`, and tears the fixtures back down when you are done.

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
- `/tw api test run [core|profile|command-links|configs|progression|interaction-extensions|trait-effects|policies|diagnostics|hydragon-integrations|all] [verbose]`
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
`run core` checks API availability, versioning, capabilities including `COMPANION_XP_EVENTS`, global config reads, and persistence diagnostics.

For API 0.9 builds, `run core` proves only the baseline capabilities it
explicitly requires. `run hydragon-integrations` separately requires capture
policy, bonded-vessel readiness, population-group reconciliation,
provisioning, and transactional profile-data capability evidence from the
packaged runtime. It also runs isolated deterministic behavioral fixtures and
does not require `prepare`, a live profile, player inventory, world fixture, or
database fixture. The suite never makes an unavailable authority available;
an absent or degraded capability is a failed assertion.

Use `/tw diagnose` alongside the self-tests. It reports the API version and
advertised capabilities, capture-policy recovery readiness, the current
bonded-vessel/group/provisioning availability, and persistence health.

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
- spawner config resolution (empty/filled item ids + by-id round-trip)
- name-item config resolution (item-id + by-id round-trip)
- command-item config resolution (item-id + by-id round-trip)

`run progression` checks:
- progression reads by profile id and NPC UUID
- controlled mutation calls for happiness/needs/breeding/traits/life-stage/attachments
- invalid-argument behavior for mutation methods that require valid input payloads
- best-effort baseline restore at the end of the suite so repeated runs stay stable

`run interaction-extensions` checks:
- requirement/effect/preset registration through the public API
- id listing and preset lookup behavior
- unregister behavior via closing returned `AutoCloseable` handles
- blank-id rejection behavior for requirement/effect/preset registration

`run trait-effects` checks:
- custom trait effect key registration through the public API
- normalized key listing behavior
- unregister behavior via closing the returned `AutoCloseable` handle
- blank-key rejection behavior

`run policies` checks:
- ownership reads
- owner positive/negative checks
- claim/damage decision coherence
- population-cap reads

`run diagnostics` checks:
- persistence diagnostics availability
- health snapshot presence
- queue-metric readability

`run hydragon-integrations` checks:
- capture-policy capability and bundled capture-mechanics resolution
- bonded-vessel capability plus `READY` vessel recovery state
- population-group capability plus `READY` reconciliation state
- companion-provisioning capability
- transactional profile-data capability
- guaranteed capture commits without invoking entropy
- failed probabilistic capture leaves its source/target immutable, emits once,
  applies nothing, and reuses the original result for a duplicate callback
- current bonded generation validates while a stale generation is rejected
- a population-group admission at the configured boundary rejects overflow
- dormant and active provisioning each commit exactly one profile
- failed active projection retains one durable `PARTIAL_DORMANT` profile that a
  fresh coordinator can find through the same operation origin

Add `verbose` to print each individual assertion instead of only the suite summaries.

`run` also logs a full verbose self-test report to the server logs on every execution, even when chat output is non-verbose.

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
- Maven tests for schema/config/API records are source-level evidence; they do
  not replace a live packaged capability advertisement and runtime self-test.



