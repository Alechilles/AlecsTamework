# Tamework–HyDragon Bonded Integration Contract

- Primary mod: Tamework
- External mod: HyDragon
- Dependency: HyDragon requires Tamework
- Public API contract: API `3.0.0` (`TameworkApi.getApiVersion()`)
- Required range in the HyDragon manifest, build, and runtime bridge: on the
  local `tamework-api-3` branch it is `>=4.3.1`, because the Tamework branch
  build still reports 4.3.1. Raise it to `>=5.0.0` when Tamework's
  `mod_version` becomes 5.0.0. Until then the range also admits the released
  4.3.1, which has the old API.
- Validation status: HyDragon unit and packaging tests pass against the branch
  jar. The live check with both mods is pending.

## Goal

HyDragon uses Tamework's bonded-companion API for full dragons and bonded
Miniwyverns. It does not import internal services, read Tamework's companion
files, keep its own copy of bonded state, or fall back to another API.

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
`BondedCompanionApi.availability()` at request time, so the bonded runtime can
become ready after startup without another restart.

Tamework's `/tw api test run hydragon-integrations` checks the same six
capabilities.

## APIs removed in 3.0.0

These APIs no longer exist in Tamework. HyDragon must not reference them or
their capabilities:

- `CommandFamilyRosterApi` (`COMMAND_FAMILY_ROSTERS`);
- `CommandTimedSummoningApi` (`COMMAND_TIMED_SUMMONING`);
- `CompanionProvisioningApi` (`COMPANION_PROVISIONING`);
- `PaidCommandRevivalApi` (`PAID_COMMAND_REVIVAL`);
- `policies().populationAdmissions()` (`NAMED_CAPACITY_RESERVATIONS`); and
- persistence resilience reads (`PERSISTENCE_RESILIENCE`).

HyDragon also does not use generic `ProfileDataApi` storage or
`PopulationGroupApi` for bonded ownership or active eligibility.
`PopulationAdmissionLocation` is kept as a stable public type.

## Feature gates

| Feature | Available behavior | Unavailable/degraded behavior |
| --- | --- | --- |
| Draconic Stone | capture into a stored bonded profile; one Stone is spent and given back when the save fails | deny before the Stone is spent; do not tame/link or create a filled item |
| Dragon Horn reads | list profile cards from `list` | show the bonded-unavailable reason |
| Summon | bring one stored profile into the world | deny without changing the stored profile |
| Dismiss/store | snapshot and remove the active body | keep the profile active; retry later |
| Paid revive | charge the family price, restore the dragon active at the placement, refund when the restore fails | keep `DEAD`; consume nothing |
| Soul Bond | provision one stored Miniwyvern and write its first extension value | do not consume or duplicate the one-lifetime grant |
| Miniwyvern attunement/abilities | compare-and-set the bonded extension and bind only while active | keep extension/profile; disable dependent mutation/runtime binding |
| Encounter/flight eligibility | require an active full-dragon lease | stored/dead/mini/invalid profiles do not qualify; unavailable authority fails closed |

Missing paid-revive context must not disable safe reads or Dismiss. Missing
capture-policy support must not disable an already stored Horn profile.

## Authority matrix

| Data or behavior | Tamework owns | HyDragon owns |
| --- | --- | --- |
| Stable profile, owner, roster, role | bonded authority | stable references only |
| Family | derived from the roster config and the role on every read; not stored | the roster assets |
| `STORED` / `ACTIVE` / `DEAD` | bonded authority | no second lifecycle |
| Snapshot and panel presentation | bonded authority | source role assets and extension presentation values |
| Lease token, body, expiry/cooldown | bonded authority | family policy values |
| Summon/store/death cleanup | bonded authority | normal dragon/Miniwyvern role behavior |
| Capture save and capture proof | bonded authority | Stone assets, allowed roles, channel/effects, balance |
| Revival quote, charge, refund | bonded authority | item IDs/quantities in policy assets |
| Miniwyvern archetype, attunement, ability/progression document | one extension value per profile and namespace | schema, merge rules, and domain behavior |
| One-lifetime Soul Bond eligibility | stored bonded profile plus HyDragon entitlement evidence | acquisition policy and request identity |
| Active full-dragon eligibility | read-only bonded roster/profile/lease query | encounter decision using that result |

The live entity UUID is never a cross-plugin durable identity. HyDragon uses
profile IDs and reads the current lease from the profile view.

## Behavior HyDragon relies on in 3.0.0

- **Revision.** `BondedCompanionProfileView.revision` is the record
  generation. Summon, store, revive, abandon, and talent requests pass it as
  `expectedRevision` and get `REVISION_CONFLICT` when it is stale. The lease
  token is the generation as text.
- **Provision.** `provision` creates a `STORED` profile with no body and no
  snapshot. The caller namespace and idempotency key identify the request: a
  repeat returns the same profile while it exists. The same key for another
  owner or roster is `VALIDATION_FAILED`. After the profile is abandoned the
  same request creates a new one.
- **First summon.** The first summon of a provisioned profile builds the body
  from its role and snapshots it. Later summons use the snapshot.
- **Capture into storage.** The capturing player owns the result. The profile
  starts with the family's summon cooldown. `findCapture` reads the proof kept
  on the profile; it is gone once the profile is abandoned.
- **Revive.** The price is charged, then the dragon is restored `ACTIVE` at
  the placement, then the charge is refunded if the restore failed. A revive
  needs a free active place in the family. This replaces the 2.x behavior
  where a revive returned the profile to `STORED`.
- **Timers.** Session length, summon cooldown, and revive cooldown come from
  the roster family, with talent modifiers. Session expiry, owner logout, and
  owner world change store the companion. Owner death does not.
- **Family limits.** `MaximumOwned` and `MaximumActive` (`0` is no limit) are
  checked in the step that saves a provision, capture, summon, or revive.
- **Lost bodies.** A body that vanished without a confirmed death is listed as
  `STORED` and can be summoned again.
- **Extension data.** One JSON value per profile and namespace. The namespace
  may not contain `/`, and `tamework` and `Alechilles:Tamework` are reserved.
  `MISSING_REVISION` (`-1`) creates the value, the first value has revision
  `0`, and each write adds one. The stored text is the text sent.
- **Reason strings.** Result reasons such as
  `bonded-transition-active_capacity_reached` and
  `bonded-transition-cooldown_active` are unchanged from 2.x.
- **Module dependency.** Bonded persistence requires generic persistence. A
  server with generic persistence off has no bonded companions.

The full list is in
`wiki/Modder-Documentation/Public-API/API-Reference/Bonded-Companion-API-Reference.md`.

## Concurrency

Profile actions are fenced by the expected revision. Two summons at once
cannot both pass the family's active limit, because the limit is checked again
in the step that saves the change.

Extension updates use owner, profile, and namespace plus the expected
extension revision. On conflict, HyDragon reads the value again and merges its
own document. It does not overwrite another revision.

One original NPC can be captured into storage once. An uncertain result is
resolved by reading the profile (`list`, `findCapture`, `getExtensionData`),
not by retrying with a new identity.

## Threading and world context

- No API call blocks a world thread on file I/O.
- A future can complete on Tamework's companion writer thread. HyDragon hops
  to the owning world with `world.execute(...)` before it touches entities,
  components, or inventories.
- Deferred work carries owner/profile/world IDs and immutable data, never a
  stale `Player`, entity reference, or live component.
- Summon and revive supply one placement in `BondedCompanionActionContext`, in
  the world the request names.
- `BondedCompanionChangedEvent` listeners run after the change, on the thread
  that made it, with no order guarantee between threads. A change that is
  undone is followed by an event for the compensating change. Listeners read
  the current profile view when they need full data.
- Events are not replayed after a restart.

## Miniwyvern extension contract

HyDragon stores Miniwyvern archetype, attunement, ability scheduler state, and
other domain data in a namespaced bonded extension value. It uses
`getExtensionData` and `compareAndSetExtensionData`, not `ProfileDataApi`.

The value survives store, summon, logout, transfer, expiration, death, revive,
and relog. It is removed when the profile is abandoned.
`MiniwyvernAbilityRuntime` subscribes to `BondedCompanionChangedEvent`: it
attaches only to an active profile and detaches when the profile becomes
stored or dead, without deleting data.

## Active full-dragon eligibility

Dynamic encounter and flight eligibility queries
`hydragon:dragon_horn`, then accepts only a profile with:

- family `hydragon:full_dragons`;
- state `ACTIVE`;
- a non-null active lease;
- the expected roster/owner; and
- a valid full-dragon role/policy match.

Stored or dead dragons, active Miniwyverns, and profiles whose family is
`tamework:unresolved` do not qualify.

## Dependency and compatibility behavior

| Runtime combination | Result |
| --- | --- |
| Tamework in declared range with `BONDED_COMPANIONS` ready | full bonded integration enabled |
| Tamework in range but bonded capability absent | bonded HyDragon features report the missing capability and change nothing |
| Capability advertised but bonded runtime unavailable | dependent gates report the bonded availability reason; no player cost |
| Tamework missing or outside manifest range | plugin dependency validation fails before gameplay |
| Tamework 4.3.1 release (API 2.x) inside the temporary range | capability and version checks fail; raise the floor at release |
| Unrelated Tamework capability degraded | only HyDragon features requiring that capability are disabled |

`manifest.json`, the build's Tamework version, and
`TameworkBridge.REQUIRED_TAMEWORK_RANGE` must stay aligned. There is no private
HyDragon bonded database.

## Diagnostics

`diagnostics().getPersistenceDiagnostics()` reports the companion folder, its
size, the writer's health, and `recordsByLocation`. It has no bonded-only
counts and exposes no owners, profile IDs, NPC UUIDs, snapshots, or extension
payloads.

HyDragon reports capability names, per-feature gate state, missing
capabilities, and the bonded availability reason. A diagnostic must never
authorize gameplay mutation by itself.

## Acceptance

- HyDragon compiles only against public Tamework API types;
- all bonded consumers require `BONDED_COMPANIONS`;
- no production call site names a removed API or capability;
- unavailable paths fail before Stone, Egg, or revive-cost consumption;
- full dragons and Miniwyverns share one Horn but keep separate families;
- Miniwyvern extension state survives every intended transition;
- active-full-dragon eligibility ignores stored, dead, and Miniwyvern
  profiles;
- manifest, build, runtime range, and packaged assets agree; and
- the live check passes: `/tw api test run` passes, HyDragon loads with every
  feature enabled, a captured dragon enters the roster and can be summoned,
  dismissed, expired, killed, and revived (cost taken, refund on a refused
  revive), the Miniwyvern soul bond is provisioned, summons for the first
  time, and survives a restart, and family limits hold.
