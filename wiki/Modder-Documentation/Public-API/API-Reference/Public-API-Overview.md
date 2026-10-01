---
title: "Public API Overview"
order: 1
published: true
draft: false
---
# Public API Overview

> **API `3.0.0`**
> `getApiVersion()` returns `"3.0.0"`. This version ships with Tamework 5.0.0
> and is not compatible with API 2.x. See "Changes from 2.x" below.

Obtain the API from the loaded Tamework plugin:

```java
Tamework plugin = Tamework.getInstance();
TameworkApi api = plugin == null ? null : plugin.getApi();
if (api == null) {
    return;
}
```

The API exists only while Tamework's companion store is ready. Read
`getApiVersion()` for compatibility diagnostics and check `getCapabilities()`
before using a surface. Capabilities, not version strings, authorize optional
behavior.

## Entry points

- `profiles()` for companion profile reads;
- `commandLinks()` for command-tool links;
- `progression()` for progression reads and supported mutations;
- `policies()` for ownership, damage, claim access, the owner-cap preflight,
  and `admissionProviders()`;
- `interactionExtensions()` and `traitEffects()` for registered extensions;
- `husbandryOutcomes()` for bounded, owner-scoped husbandry modifiers;
- `profileData()` for namespaced data on a companion;
- `events()` for immutable notifications;
- `activities()` for the live activity feed;
- `configs()` for detached config views;
- `diagnostics()` for the read-only companion store snapshot;
- `populationGroups()` for role classification and counts;
- `requiredContentProfiles()` for managed-content readiness;
- `bondedCompanions()` for roster companions that are summoned, stored,
  revived, captured, and provisioned;
- `capturedItemDisplay()` for captured-item tooltip and quality providers;
- `commandUi()` for registered Java command-menu renderers and namespaced
  contributors; and
- `commandHud()` for registered Java target and equipped-tool HUD renderers
  and contributors.

## Capabilities

Always advertised in 3.0.0: `PROFILES`, `COMMAND_LINKS`, `PROGRESSION`,
`PROGRESSION_MUTATIONS`, `POLICY`, `INTERACTION_EXTENSIONS`, `TRAIT_EFFECTS`,
`PROFILE_DATA`, `PROFILE_DATA_TRANSACTIONS`, `EVENTS`, `COMPANION_XP_EVENTS`,
`CONFIG_READ`, `DIAGNOSTICS`, `POPULATION_GROUPS`,
`DURABLE_POPULATION_GROUP_COUNTS`, `DURABLE_DEPLOYABLE_POPULATION_COUNTS`,
`EXTERNAL_ADMISSION_PROVIDERS`, `REQUIRED_CONTENT_PROFILES`,
`CAPTURE_TAME_AND_LINK`, and `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`.

Advertised while their runtime is available: `CAPTURE_POLICY`,
`BONDED_COMPANIONS`, `ACTIVITY_FEED_V2`, `REVIVAL_ACTIVITY_CONTEXT`,
`COMMAND_UI_RENDERERS`, `COMMAND_UI_CONTRIBUTORS`,
`COMMAND_UI_CUSTOM_ACTIONS`, `COMMAND_UI_CUSTOM_FLOWS`,
`COMMAND_HUD_RENDERERS`, `COMMAND_HUD_CONTRIBUTORS`, `HUSBANDRY_OUTCOMES`,
`HUSBANDRY_TOOL_CONTEXT`, `HUSBANDRY_TOOL_BONUSES`, `HUSBANDRY_CARE_BONUSES`,
`HUSBANDRY_FLAT_CARE_BONUS`, `HUSBANDRY_BREEDING_GENETICS`, and
`CAPTURED_ITEM_DISPLAY`.

In the enum but not advertised in 3.0.0: `LOADED_POPULATION_GROUP_COUNTS` and
`DURABLE_OUTPUT_OPERATIONS`.

An unadvertised capability is unavailable. Do not infer support from the API
version or from DTO classes being present.

## Changes from 2.x

Removed accessors:

| Removed | Use instead |
| --- | --- |
| `commandFamilyRosters()` | No public replacement. `CommandFamilyRosterMembershipChangedEvent` still reports roster changes. |
| `commandTimedSummoning()` | `bondedCompanions().summon` and `store` for bonded rosters. |
| `companionProvisioning()` | `bondedCompanions().provision`. |
| `paidCommandRevival()` | `bondedCompanions().quoteRevive` and `revive`. |
| `policies().populationAdmissions()` | Tamework checks limits itself. Add policy with `policies().admissionProviders()`. |
| `populationGroups().getReconciliationStatus()` | None. Counts are always current. |
| `profileData().findOperation(...)` | `profileData().getVersioned(...)`. |
| `diagnostics().getPersistenceResilience()`, `queryPersistenceAvailability(...)`, `findPersistenceIncident(...)` | `diagnostics().getPersistenceDiagnostics()`. |

Removed capabilities: `COMMAND_FAMILY_ROSTERS`, `COMMAND_TIMED_SUMMONING`,
`COMPANION_PROVISIONING`, `PAID_COMMAND_REVIVAL`,
`NAMED_CAPACITY_RESERVATIONS`, and `PERSISTENCE_RESILIENCE`. A consumer that
resolves capability names by text must drop these names, or it never becomes
ready.

Changed behavior:

- Diagnostics keep their field names. The SQLite sizes read `0` and four
  fields were added. See [Diagnostics](/mod/alecs-tamework/diagnostics-api-reference).
- `NpcProfileChangedEvent` has four new fields, `ProfileChangeType` has
  `LOCATION` and `RELEASED`, and events are delivered on the changing thread
  after the store lock is released. See [Events](/mod/alecs-tamework/events-api-reference).
- Profile data namespaces may not contain `/`. See
  [Profile Data](/mod/alecs-tamework/profile-data-api-reference).
- The deployable group count counts `LIVE` companions only. See
  [Population Groups](/mod/alecs-tamework/population-groups-api-reference).
- Admission providers fail closed, a domain limit of `0` admits nothing, and
  there is no admin bypass. See
  [Admission Providers](/mod/alecs-tamework/admission-providers-api-reference).
- Bonded companions are stored with all other companions, a revive returns the
  companion active, and bonded persistence requires generic persistence. See
  [Bonded Companions](/mod/alecs-tamework/bonded-companion-api-reference).
- `profiles().getActiveSnapshot(...)` returns a small JSON description, not the
  full body snapshot, and `commandLinks().getHomePosition(...)` is reliable
  only while the companion's body is loaded.

The record and view classes of the removed APIs are still in the jar for now.
They will be deleted. Do not build on them.

## Threading

- Reads are synchronous and do not block. They are safe from any thread.
- Futures and stages from write methods can complete on the companion store's
  writer thread. Do not touch entities, components, or worlds in a
  continuation. Hop to the owning world with `world.execute(...)` first.
- Event listeners run on the thread that made the change.

## Profile data and bonded extension data

Use `profileData()` for integration state on an ordinary companion and
`BondedCompanionApi` extension data for a bonded companion. Both store JSON on
the companion's record under a namespace.

Do not use a live NPC UUID or a command-item row as a substitute for the
profile ID, and never read or write Tamework's companion files directly.
