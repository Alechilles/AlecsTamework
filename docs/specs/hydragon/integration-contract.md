# Tamework–HyDragon Integration Contract

- Primary mod: Tamework
- External mod: HyDragon
- Dependency: HyDragon requires Tamework
- Version range: Tamework `>=3.0.0 <4.0.0`; HyDragon 0.2.x
- Project profile/plugin set: Tamework plus HyDragon; exact asset profile is
  recorded when asset implementation begins
- Failure behavior: independent capability gates fail closed before player cost
- Validation: dependency on/off, each capability unavailable/degraded/available,
  and complete affected-consumer paths

Status: required recovery contract; implementation in progress

## Goal

HyDragon consumes versioned public Tamework capabilities and immutable DTOs. It
does not link against internal services, access SQLite, mirror canonical
companion state, or infer capability availability from version alone.

## Required capabilities

| Capability | Authority |
| --- | --- |
| `PROFILES` | Stable profile/alias identity |
| `PROFILE_DATA` and `PROFILE_DATA_TRANSACTIONS` | Namespaced HyDragon state |
| `POLICY` | Ownership and lifecycle policy |
| `PERSISTENCE_RESILIENCE` | Health, incidents, and operation recovery |
| `CAPTURE_POLICY` | Capture configuration and requirement handlers |
| `POPULATION_GROUPS` | Group membership, counts, and admission |
| `COMPANION_PROVISIONING` | Soul Bond profile grant/activation |
| `COMMAND_FAMILY_ROSTERS` | Dragon Horn roster |
| `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION` | One result and source spend |
| `CAPTURE_TAME_AND_LINK` | In-place successful capture |
| `COMMAND_TIMED_SUMMONING` | Summon, Dismiss, expiry, and cooldown |
| `PAID_COMMAND_REVIVAL` | Quote, exact charge, restore, and compensation |

Optional/deferred:

- `COMPANION_INVENTORY`

Removed:

- `BONDED_VESSELS`

## Feature gates

| HyDragon feature | Required recovery capabilities | Unavailable/degraded behavior |
| --- | --- | --- |
| Probabilistic stone attempt | capture policy, population groups, resolved-attempt consumption | deny before entropy and source spend |
| Tame in place/add to Horn | above plus roster and tame/link | deny before entropy and source spend |
| Wyvern Egg claim | population groups, provisioning, roster | deny before Egg consumption |
| Horn roster/commands | roster | preserve rows; disable unsafe mutation |
| Timed Summon/Dismiss | roster, population groups, timed summoning | preserve lease/profile; deny new projection |
| Paid revival | roster, population groups, timed summoning, paid revival | preserve dead row; consume nothing |
| Elemental profile state | profile data transactions | deny essence consumption |
| Miniwyvern backpack | companion inventory | feature absent; Miniwyvern itself remains supported |

Missing paid revival must not disable safe Horn commands. Missing companion
inventory must not disable Miniwyvern provisioning, roster membership, or
ordinary behavior.

## Authority matrix

| Data/behavior | Owner | Consumer rule |
| --- | --- | --- |
| Profile, alias, owner, lifecycle, death/lost state | Tamework | HyDragon stores stable references only |
| Capture result, cooldown, and source spend | Tamework | HyDragon never pre-rolls or mirrors |
| Roster membership and command preferences | Tamework | Horn metadata is disposable projection |
| Group membership/count/admission | Tamework | HyDragon supplies config, not counters |
| Summon lease/storage/cooldown | Tamework | HyDragon supplies role balance |
| Revival quote/charge/refund | Tamework | HyDragon supplies item recipe/presentation |
| Soul Bond entitlement | HyDragon | Tamework provisions exactly one profile |
| Elemental/archetype state | HyDragon namespace in Tamework profile data | Cannot represent lifecycle/roster authority |
| Stone/Horn/Egg assets and balance | HyDragon | Tamework treats IDs as content data |

## Idempotency

Cross-plugin mutations use:

```text
hydragon:<operation-kind>:<stable-player-action-id>
```

The key survives callbacks, async continuation, relog, and restart. Tamework
returns the recorded result for duplicates.

Rules:

1. Preflight queries are advisory and side-effect-free.
2. Positive work uses prepare/live-apply/durable/publish or compensation.
3. Inventory uses exact slot/stack/revision receipts.
4. Capture entropy is resolved once.
5. Source spend and revival charge are never repeated.
6. Ambiguity remains pending/contained; callers do not guess failure.
7. A terminal result and spendable compensation cannot both exist.
8. Listener or presentation failure cannot change committed state.

## Threading

- Blocking persistence runs off world threads.
- ECS and inventory live access resolves current entities/components on the
  owning world thread.
- Deferred work carries stable IDs and immutable snapshots, never stale
  `Player` components or entity refs.
- Cross-plugin events are immutable post-commit notifications unless explicitly
  documented as synchronous, side-effect-free preflight handlers.

## Compatibility matrix

| Tamework | HyDragon | Integration state | Static | Live | Notes |
| --- | --- | --- | --- | --- | --- |
| Current reduced 3.0 dev build | 0.2.x | recovery capabilities absent | must fail closed | not release-valid | No required capability may be inferred |
| Feature-slice candidate | 0.2.x | individual capabilities independently enabled | per-slice tests | per-slice smoke | Unfinished dependencies remain unavailable |
| Feature-complete 3.0 candidate | 0.2.x | all required capabilities available | full cross-repo suite | full live matrix | Only release-eligible combination |
| Tamework absent/incompatible | 0.2.x | dependency failure | startup explains range | no gameplay mutation | No private HyDragon fallback DB |
| Tamework feature degraded | 0.2.x | affected capability degraded | deterministic denial | no player cost | Unrelated safe capabilities remain usable |

## Diagnostics

Required read-only summaries:

- capability availability and stable reason;
- command family by owner/family;
- population groups/counts/admission;
- provisioning by operation/profile;
- summon by operation/profile;
- capture attempt by operation;
- revival by operation/profile;
- bounded persistence incidents/quarantines/circuits.

Routine diagnostics omit secret entropy, full inventory contents, and unrelated
player data.

## Acceptance

- HyDragon compiles only against public Tamework APIs;
- every feature denies before cost when a dependency is unavailable;
- capture, provisioning, timed storage, and revival return prior results on
  retry;
- one profile/roster/lease survives world movement, unload, death, relog, and
  restart;
- no bonded-vessel or private companion-inventory authority exists;
- integration-on and dependency/capability-off paths are both tested.
