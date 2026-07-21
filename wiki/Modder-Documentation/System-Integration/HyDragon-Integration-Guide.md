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
that need capture, durable item bindings, group limits, or ritual provisioning.

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
| Probabilistic Draconic Stone capture | `PROFILES`, `POLICY`, `PERSISTENCE_RESILIENCE`, `CAPTURE_POLICY`, `POPULATION_GROUPS` | Disable before entropy or mutation; retain item and target unchanged. |
| Bonded summon/store/repair | Shared capabilities plus `BONDED_VESSELS` | Retain durable binding/profile/item state; do not emulate locally. |
| Soul Bond Miniwyvern provisioning | `PROFILES`, `POLICY`, `PERSISTENCE_RESILIENCE`, `POPULATION_GROUPS`, `COMPANION_PROVISIONING`; shipped ritual also needs `INTERACTION_EXTENSIONS` | Do not create a HyDragon-local profile. |
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
projection authority; bonded vessels require recovered operations and every
exact evidence/mutation port. A failed prerequisite omits only that capability
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

## Command delivery limitation

Tamework's current shipped command-tool path is the example-only
`Tamework_Command_Whistle_Example` / `TwCommandExample` pair. It can exercise
the HyDragon-relevant `Follow`, `Hold`, `Recall`, and `AttackTarget` commands,
but it has no recipe or other polished player-acquisition path. In its shipped
form it is an operator-given or development-config item.

HyDragon must therefore either ship its own localized production command item,
config, and recipe/acquisition path, or explicitly document that an operator
must give the example item. The integration must not imply that Tamework 3.0.0
already provides a finished player acquisition flow.

## Ownership boundary

Tamework owns:

- canonical profile identity, owner, role, lifecycle, and revision;
- population membership, counts, reservations, and reconciliation;
- bonded-vessel binding, generation, state, projection evidence, and operation
  journal; and
- exactly-one companion provisioning and recovery.

HyDragon owns:

- dragon roles/assets, Draconic Stone tiers, capture values, altar recipes, and
  encounter policy;
- Soul Bond player entitlement and ritual presentation;
- Revitalizing Essence consumption/refund as an idempotent HyDragon saga;
- elemental archetypes and ability state under its own profile-data namespace;
  and
- localized English, Brazilian Portuguese, German, French, and Spanish player
  text.

HyDragon does not read `tamework.sqlite`, import internal implementation
classes, cache mutable `Tw*Config` objects, or use item metadata as canonical
binding state.

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
roll. A failed roll retains the source item and leaves NPC state unchanged.

## Bonded stones and repair

A bonded stone represents one profile across stored, active, dead, lost, and
released states. Binding generation increments fence stale or copied item
projections.

Repair uses one stable HyDragon idempotency key. If material consumption and a
Tamework transition straddle a restart, HyDragon queries/resumes the original
operation. It never refunds after `APPLIED`, and it never invents a new key
because the result is unavailable or timed out. Only a proven pre-apply
`TERMINAL_DENIED` authorizes compensation.

To start a damaged-stone repair from a held item, HyDragon first calls
`resolveHeldItemProjection` with exact holder/container/slot revision and
fingerprint evidence and requires an authoritative `DEAD` binding result. It
does not parse private metadata into a binding ID or profile revision.

## Soul Bond and Miniwyverns

Soul Bond calls generic companion provisioning with a stable origin. The role
belongs to `hydragon:soulbound_mini`, limited to one owned and one active per
owner. An active projection failure may leave the one profile as recoverable
`PARTIAL_DORMANT`; retry resumes it instead of creating a second Miniwyvern.

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
packaged API 0.9 capabilities and isolated capture, stale-vessel, group-limit,
and provisioning/recovery behaviors. Only suites present in the command's
usage output are available in that build.

Use `/tw diagnose population` for group reconciliation and owner/claim
reservation evidence, `/tw diagnose vessel <binding-or-profile>` for one
binding's generation/lifecycle/evidence and active-operation correlation, and
`/tw diagnose provisioning <caller-namespace> <idempotency-key>` for one
durable provisioning origin. These exact lookups are bounded, sanitized, and
read-only.

## Related pages

- [Capture Policy API Reference](/mod/alecs-tamework/capture-policy-api-reference)
- [Bonded Vessels API Reference](/mod/alecs-tamework/bonded-vessels-api-reference)
- [Population Groups API Reference](/mod/alecs-tamework/population-groups-api-reference)
- [Companion Provisioning API Reference](/mod/alecs-tamework/companion-provisioning-api-reference)
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
