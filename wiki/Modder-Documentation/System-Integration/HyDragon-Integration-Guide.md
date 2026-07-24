---
title: "HyDragon Integration Guide"
order: 9
published: true
draft: false
---
# HyDragon Integration Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

This guide describes the supported boundary between HyDragon and Tamework
3.0.0. It is also a reference pattern for other companion-expansion plugins
that need probabilistic capture, durable command rosters, timed summons, group
limits, or ritual provisioning.

## Version and bootstrap

HyDragon declares a required dependency range:

```json
{
  "Dependencies": {
    "Alechilles:Alec's Tamework!": ">=3.0.0 <4.0.0"
  }
}
```

At startup it obtains `TameworkApi` from
`Tamework.getInstance().getApi()`, null-checks both values, logs the independent
API version, and gates each feature by capability. API `0.9.0` remains
experimental; version equality is not an authority check.

## Capability matrix

| HyDragon feature | Required capabilities | Missing result |
| --- | --- | --- |
| Probabilistic Draconic Stone capture | `PROFILES`, `POLICY`, `PERSISTENCE_RESILIENCE`, `CAPTURE_POLICY`, `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`, `CAPTURE_TAME_AND_LINK`, `POPULATION_GROUPS`, `COMMAND_FAMILY_ROSTERS`, `COMMAND_TIMED_SUMMONING` | Disable before entropy or mutation; do not consume an item or alter the target. |
| Wyvern Egg provisioning and Dragon Horn link | `PROFILES`, `POLICY`, `PERSISTENCE_RESILIENCE`, `POPULATION_GROUPS`, `COMPANION_PROVISIONING`, `COMMAND_FAMILY_ROSTERS`, `COMMAND_TIMED_SUMMONING`; shipped ritual also needs `INTERACTION_EXTENSIONS` | Do not consume the egg or create a HyDragon-local profile. |
| Dragon Horn summon/storage | `COMMAND_FAMILY_ROSTERS`, `COMMAND_TIMED_SUMMONING` | Keep the profile durably stored and disable summon. |
| Elemental/profile-scoped transactional state | `PROFILE_DATA`, and `PROFILE_DATA_TRANSACTIONS` when the state participates in an idempotent gameplay transaction | Disable the affected persistence-dependent behavior; do not treat queued legacy writes as committed. |
| Post-commit presentation | `EVENTS` | Disable listeners/presentation; never reinterpret mutation status. |
| Operator health bridge | `DIAGNOSTICS` | Warn that integrated diagnostics are unavailable. |

If a new API 0.9 type is present but its capability is absent, the feature is
unavailable by design. Default accessors return empty or fail-closed facades.

Tamework 3.0.0 installs production implementations for each API 0.9 authority,
but does not advertise them optimistically. Transactional profile data requires
its migrated repository; capture policy requires bounded attempt recovery;
population groups require group recovery, reconciliation, and canonical-path
installation; provisioning requires its recovered journals and requested
projection authority. A failed prerequisite omits only that capability
and leaves its facade fail closed. `/tw diagnose` is the operator's concise
source of truth for the packaged server.

## Fixed integration decisions

- Miniwyverns are Soul Bond-exclusive. Do not include their wild or tamed roles
  in ordinary Draconic Stone allowlists or capture policies.
- Flight uses the bundled item ID `Tamework_Flightmasters_Talisman`.
- HyDragon targets Tamework `>=3.0.0 <4.0.0`; Tamework 2.x is unsupported.
- The companion backpack is deferred. No API 0.9 capability or schema-v8 table
  represents it.
- HyDragon has no released legacy data, so this integration requires no
  HyDragon-specific item-ID or inventory migration path.

## Command-family model

HyDragon ships its own localized Dragon Horn and maps it to one stable command
family. The physical item is an access tool; roster membership is canonical per
owner, command family, and profile. A replaced Horn therefore resolves the same
roster instead of stranding companions in item-local metadata.

The roster's `activeForBulkCommands` flag controls selection for group commands.
It does not mean the companion currently has a live world projection. Timed
summoning owns that separate state.

## Ownership boundary

Tamework owns:

- canonical profile identity, owner, role, lifecycle, and revision;
- population membership, counts, reservations, and reconciliation;
- owner/command-family roster membership and bulk-command selection;
- summon leases, per-family active caps, remaining-time checkpoints, storage,
  cooldowns, and restart recovery;
- dead-companion snapshots and the normal free roster respawn/restoration
  path; and
- exactly-one companion provisioning and recovery.

HyDragon owns:

- dragon roles/assets, Draconic Stone tiers, capture values, altar recipes, and
  encounter policy;
- Soul Bond player entitlement and ritual presentation;
- elemental archetypes and ability state under its own profile-data namespace;
  and
- localized English, Brazilian Portuguese, German, French, and Spanish player
  text.

HyDragon does not read `tamework.sqlite`, import internal implementation
classes, cache mutable `Tw*Config` objects, or use item metadata as canonical
roster state.

When HyDragon must coordinate a profile-scoped value with another durable
effect, it uses a revisioned read plus idempotent compare-and-set and queries
the same namespace/idempotency origin after restart. Legacy `put` queue
acceptance is not proof of a committed cross-domain operation.

## Capture and full-dragon groups

Draconic Stone configs opt into `ChanceMode: Probability`. Role difficulty is
authored once in `Server/Tamework/CapturePolicies`. Full dragon roles are
assigned to `hydragon:full_dragons`, normally with unlimited owned profiles and
one active profile per owner.

The capture runtime performs final live revalidation before its one terminal
roll. Every eligible resolved attempt consumes exactly one stone, whether the
roll succeeds or fails. A successful roll tames the existing NPC in place,
commits its canonical profile to the owner's Dragon Horn roster, and does not
create a filled stone. A failed roll leaves the NPC unchanged and applies the
configured retry cooldown.

## Dragon Horn summoning and respawn

Each linked dragon remains one durable profile in the Horn roster. Summoning
uses a persisted lease with a configured maximum active count and per-profile
duration. When the lease expires, Tamework stores/despawns the live projection
and returns the profile to the effectively captured state. Remaining time is
checkpointed so unloads and restarts cannot reset the limit.

Dead profiles stay in the roster. They use Tamework's normal free respawn path,
subject to the configured revive policy, cooldown, admission checks, and safe
placement. Respawn restores the saved companion identity and state; HyDragon
does not define or consume revival items.

## Soul Bond and Miniwyverns

The Wyvern Egg calls generic provision-and-link with a stable origin. The role
belongs to `hydragon:soulbound_mini`, limited to one owned profile per owner and
subject to the Dragon Horn's active cap and summon duration. A projection
failure may leave the one profile as recoverable `PARTIAL_DORMANT`; retry
resumes it instead of creating a second Miniwyvern. No separate Soul Bound
Wyvern item is created.

HyDragon entitlement remains player-scoped HyDragon data. The Miniwyvern
profile and population capacity remain Tamework authority.

## Diagnostics and validation

Before enabling a gameplay surface:

1. Verify the required capability set.
2. Query the appropriate readiness/diagnostic view where available.
3. Use mutation-bound admission or transition APIs; a read-only cap/preflight
   never authorizes a later mutation.
4. Preserve operation IDs and idempotency keys across retries.
5. Keep world mutations on the owning world thread and durable calls
   asynchronous.

Operators can use `/tw debugdb health`, `/tw debugdb integrity`, incident
inspection/retry, and redacted exports. `/tw api test run
hydragon-integrations` needs no prepared live fixture: it validates the
packaged API 0.9 capabilities and isolated capture, group-limit, and
provisioning/recovery behaviors. Only suites present in the command's
usage output are available in that build.

Use `/tw diagnose population` for group reconciliation and owner/claim
reservation evidence, `/tw diagnose command-family [owner-uuid] [family]` for
roster membership, `/tw diagnose timed [operation-or-profile]` for summon
leases, and either provisioning form for a durable provisioning origin. These
lookups are bounded, sanitized, and read-only.

## Related pages

- [Capture Policy API Reference](/mod/alecs-tamework/capture-policy-api-reference)
- [Population Groups API Reference](/mod/alecs-tamework/population-groups-api-reference)
- [Companion Provisioning API Reference](/mod/alecs-tamework/companion-provisioning-api-reference)
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
