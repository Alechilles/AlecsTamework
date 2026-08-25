---
title: "Public API Overview"
order: 1
published: true
draft: false
---
# Public API Overview

Obtain the API from the loaded Tamework plugin:

```java
Tamework plugin = Tamework.getInstance();
TameworkApi api = plugin == null ? null : plugin.getApi();
if (api == null) {
    return;
}
```

Read `getApiVersion()` for compatibility diagnostics and check
`getCapabilities()` before using a surface. Capabilities, not version strings,
authorize optional behavior.

## Entry points

- `profiles()` for canonical companion profile reads;
- `commandLinks()` for command-tool links;
- `progression()` for progression reads and supported mutations;
- `policies()` for ownership, damage, claim access, durable owner-cap
  preflight, and mutation-bound owner admission;
- `interactionExtensions()` and `traitEffects()` for registered extensions;
- `profileData()` for namespaced canonical-profile data;
- `events()` for immutable notifications;
- `configs()` for detached config views;
- `diagnostics()` for the retained read-only diagnostic snapshot;
- `populationGroups()` for role classification, counts, and reconciliation;
- `commandFamilyRosters()` for durable owner/family/profile membership;
- `commandTimedSummoning()` for timed summon, storage, and lease state;
- `companionProvisioning()` for idempotent dormant grants and activation;
- `paidCommandRevival()` for exact quote, same-profile revival, and operation
  recovery; and
- `bondedCompanions()` for the separate three-state profile, temporary lease,
  paid revive, capture-evidence, and namespaced extension-data authority; and
- `commandUi()` for registered Java command-menu renderers and namespaced
  contributors with detached snapshots, server-authoritative actions, partial
  UI updates, and built-in or custom flows.

The current capability enum contains `PROFILES`, `COMMAND_LINKS`,
`PROGRESSION`, `PROGRESSION_MUTATIONS`, `POLICY`,
`INTERACTION_EXTENSIONS`, `TRAIT_EFFECTS`, `PROFILE_DATA`, `EVENTS`,
`COMPANION_XP_EVENTS`, `CONFIG_READ`, `DIAGNOSTICS`, `CAPTURE_POLICY`,
`PROFILE_DATA_TRANSACTIONS`, `PERSISTENCE_RESILIENCE`, `POPULATION_GROUPS`,
`COMPANION_PROVISIONING`, `COMMAND_TIMED_SUMMONING`,
`PAID_COMMAND_REVIVAL`, `COMMAND_FAMILY_ROSTERS`,
`CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`, `CAPTURE_TAME_AND_LINK`, and
`BONDED_COMPANIONS`, `COMMAND_UI_RENDERERS`, `COMMAND_UI_CONTRIBUTORS`,
`COMMAND_UI_CUSTOM_ACTIONS`, and `COMMAND_UI_CUSTOM_FLOWS`. The separate
bonded surface advertises its capability only when its own database and
runtime are ready.

## Bonded companion boundary

`BondedCompanionApi` is for durable profiles whose live NPCs are temporary
projections. It uses only `STORED`, `ACTIVE`, and `DEAD`, identifies panel
records by stable bonded profile ID, and stores integration data in its own
owner/profile/namespace extension authority.

It is not an alias for the generic provisioning, command-family roster,
timed-summon, paid-revival, population-group, or profile-data APIs. Those
surfaces remain supported for ordinary integrations. A caller must not combine
the generic and bonded authorities for one companion or fall back from bonded
operations to generic persistence when `BONDED_COMPANIONS` is unavailable.

See [Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference).

## Canonical profile extension data

This section applies to generic canonical profiles. Bonded profiles use the
owner/profile/namespace extension methods on `BondedCompanionApi` instead.

Require `PROFILE_DATA_TRANSACTIONS` before using versioned profile data.
`compareAndSet` uses the expected revision and a stable idempotency key.
`findOperation` lets a caller recover the durable result after restart.

This is the supported place for integration-owned companion state. Do not use a
live NPC UUID, command-item row, or direct SQLite write as a substitute for the
canonical profile ID.

An unadvertised capability is unavailable. Integrations must not infer private
operations from the API version.

Use the public population, roster, timed-summon, provisioning, and paid-revival
authorities above. Resolved capture consumption and tame/link behavior are
advertised contracts on the capture path. None of these capabilities permits
direct access to internal replacement-persistence classes or SQLite tables.
