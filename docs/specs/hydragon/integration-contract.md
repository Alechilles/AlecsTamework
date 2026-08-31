# Tamework–HyDragon Bonded Integration Contract

- Primary mod: Tamework
- External mod: HyDragon
- Dependency: HyDragon requires Tamework
- Required range in HyDragon manifest, Maven property, and runtime bridge:
  Tamework `>=3.0.0 <4.0.0`
- Public API contract: experimental API `0.9.0`
- Validation status: automated API/asset integration covered; clean packaged
  alignment and fresh-world gameplay acceptance pending

This document does not assert validation against an exact Hytale `0.5.6`
schema profile. That exact profile is not present in the local schema catalog;
the implementation is validated through the registered codecs, Java contract
tests, current asset validator, packaging checks, and bounded live acceptance.

## Goal

HyDragon consumes Tamework's dedicated bonded-companion API for full dragons
and bonded Miniwyverns. It does not import internal services, open Tamework
SQLite files, mirror bonded lifecycle/lease state, or fall back to generic
companion persistence.

## Required capabilities

| HyDragon feature | Required Tamework capabilities |
| --- | --- |
| Dragon Horn roster/actions | `BONDED_COMPANIONS` |
| Timed summon/store | `BONDED_COMPANIONS` |
| Paid bonded revival | `BONDED_COMPANIONS` |
| Soul Bond claim/provision | `BONDED_COMPANIONS` |
| Miniwyvern attunement | `BONDED_COMPANIONS` |
| Miniwyvern abilities | `BONDED_COMPANIONS` |
| Draconic capture and roster | `BONDED_COMPANIONS`, `CAPTURE_POLICY`, `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`, `INTERACTION_EXTENSIONS`, `EVENTS` |
| Dynamic encounters | `BONDED_COMPANIONS`, `CAPTURE_POLICY`, `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`, `INTERACTION_EXTENSIONS`, `EVENTS` |
| Tamework diagnostic integration | `DIAGNOSTICS` |

HyDragon checks the advertised capability names and
`BondedCompanionApi.availability()` at request time. This allows the bonded
runtime to become ready after startup without requiring another server restart.

## Superseded HyDragon bindings

Bonded full dragons and Miniwyverns no longer use:

- `CommandFamilyRosterApi`;
- `CommandTimedSummoningApi`;
- `CompanionProvisioningApi`;
- `PaidCommandRevivalApi`;
- generic `ProfileDataApi` extension storage;
- `PopulationGroupApi` for bonded ownership/active eligibility; or
- generic profile, lifecycle, alias, outbox, or evidence readiness.

These public surfaces remain supported in Tamework for ordinary companions and
other mods. Their absence is not part of this refactor; only HyDragon's old
route through them is removed.

## Feature gates

| Feature | Available behavior | Unavailable/degraded behavior |
| --- | --- | --- |
| Draconic Stone | resolved probabilistic capture into a stored bonded profile | deny before entropy/source spend; do not tame/link or create a filled item |
| Dragon Horn reads | list complete profile-first cards | show a specific bonded-unavailable reason; never read generic roster rows |
| Summon | create one exact lease/projection | deny without deleting or rewriting the stored profile |
| Dismiss/store | snapshot and retire the active projection | retain exact active evidence/recovery; never route through generic relocation |
| Paid revive | quote and atomically reserve the complete family recipe | keep `DEAD`; consume nothing |
| Soul Bond | provision one stored Miniwyvern and initialize its extension | do not consume or duplicate the one-lifetime grant |
| Miniwyvern attunement/abilities | CAS the bonded extension and bind only while active | retain extension/profile; disable dependent mutation/runtime binding |
| Encounter/flight eligibility | require a confirmed active full-dragon lease | stored/dead/mini/invalid profiles do not qualify; unavailable authority fails closed |

Missing paid-revive context must not disable safe reads or Dismiss. Missing
capture-policy support must not disable an already stored Horn profile. Feature
gates remain independent except where their complete contract requires the same
bonded capability.

## Authority matrix

| Data or behavior | Tamework owns | HyDragon owns |
| --- | --- | --- |
| Stable profile, owner, roster/family, role | bonded authority | stable references only |
| `STORED` / `ACTIVE` / `DEAD` | bonded authority | no second lifecycle |
| Full snapshot and panel presentation | bonded authority | source role assets and extension presentation values |
| Lease token, exact projection, expiry/cooldown | bonded authority | declarative family policy values |
| Projection spawn/store/death cleanup | bonded authority | normal dragon/Miniwyvern role behavior |
| Capture durability and original-source proof | bonded authority | Stone assets, allowed roles, channel/effects, balance |
| Revival quote, atomic charge, recovery | bonded authority | item IDs/quantities in policy assets |
| Miniwyvern archetype, attunement, ability/progression document | owner/profile/namespace extension row | schema, merge rules, and domain behavior |
| One-lifetime Soul Bond eligibility | stored bonded profile plus HyDragon entitlement evidence | acquisition policy and request identity |
| Active full-dragon eligibility | read-only bonded roster/profile/lease query | encounter decision using that result |

The live entity UUID is never a cross-plugin durable identity. HyDragon uses
profile IDs and, where necessary, reads the current lease summary returned by
Tamework.

## Idempotency and concurrency

Cross-plugin mutations use a stable caller namespace and idempotency key that
survive callbacks, async continuation, relog, and restart. Equivalent repeats
return the durable result. A reused key with different payload is rejected.

Profile actions include the expected profile revision. Extension updates use
owner/profile/namespace plus expected extension revision and
`MISSING_REVISION` for create-only writes. On conflict, HyDragon reloads and
merges its domain document; it does not overwrite another revision.

Capture entropy is resolved once, and one original source UUID can create only
one bonded profile. Revival reserves its complete ordered recipe as one batch.
Ambiguous work remains contained; HyDragon never guesses success/failure or
retries with a new identity to force progress.

## Threading and world context

- Blocking storage work does not run on Hytale world threads.
- ECS, placement, entity removal, and player inventory access resolve on the
  owning world thread.
- Deferred work carries owner/profile/world/operation IDs and immutable data,
  never a stale `Player`, entity reference, or live component.
- Summon supplies one world-qualified placement in
  `BondedCompanionActionContext`.
- Store validates the exact current lease world.
- Event listeners consume immutable post-commit notifications and read the
  current profile view when full data is needed.

## Miniwyvern extension contract

HyDragon stores Miniwyvern archetype, attunement, ability scheduler state, and
other domain data in a namespaced bonded extension document. It uses
`getExtensionData` and `compareAndSetExtensionData`, not generic
`ProfileDataApi`.

The document survives store, summon, logout, transfer, expiration, death,
revive, and relog. `MiniwyvernAbilityRuntime` subscribes to
`BondedCompanionChangedEvent`: it attaches only to an exact active projection
and detaches when the profile becomes stored or dead without deleting data.

## Active full-dragon eligibility

Dynamic encounter and flight eligibility queries
`hydragon:dragon_horn`, then accepts only a profile with:

- family `hydragon:full_dragons`;
- state `ACTIVE`;
- a non-null active lease;
- the expected roster/owner; and
- a valid full-dragon role/policy match.

Stored or dead dragons, active Miniwyverns, stale projections, and generic
population evidence do not qualify.

## Dependency and compatibility behavior

| Runtime combination | Result |
| --- | --- |
| Tamework in declared range with `BONDED_COMPANIONS` ready | full bonded integration enabled |
| Tamework in range but bonded capability absent | bonded HyDragon features report missing capability and do not mutate generic persistence |
| Capability advertised but bonded runtime unavailable | dependent gates report the bonded availability reason; no player cost |
| Tamework missing or outside manifest range | plugin dependency validation fails before gameplay |
| Unrelated Tamework capability degraded | only HyDragon features requiring that capability are disabled |

`manifest.json`, the Maven Tamework version/path, and
`TameworkBridge.REQUIRED_TAMEWORK_RANGE` must stay aligned. There is no private
HyDragon bonded database or legacy generic fallback.

## Diagnostics

Tamework reports redacted aggregate bonded readiness, schema version, state
counts, active leases, bounded cleanup count, and a fixed failure category.
`/tw debug persistence export` includes `bonded-companions.json` without owners, profile
IDs, NPC UUIDs, snapshots, or extension payloads.

HyDragon reports capability names, per-feature gate state, missing
capabilities, and the bonded availability reason. A diagnostic must never
authorize gameplay mutation by itself.

## Acceptance

- HyDragon compiles only against public Tamework API types;
- all bonded consumers require `BONDED_COMPANIONS`;
- old generic bonded routes have no production call sites;
- unavailable paths fail before Stone, Egg, or revive-cost consumption;
- full dragons and Miniwyverns share one Horn but retain separate families;
- Miniwyvern extension state survives every intended transition;
- active-full-dragon eligibility ignores stored, dead, Miniwyvern, and generic
  population evidence;
- manifest, Maven, runtime range, and packaged assets agree; and
- both dependency-on and capability-off behavior are covered before release.
