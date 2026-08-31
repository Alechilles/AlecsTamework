---
title: "HyDragon Integration Guide"
order: 8
published: true
draft: false
---
# HyDragon Integration Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

HyDragon uses Tamework's dedicated bonded-companion lease model for full
dragons and bonded Miniwyverns. Durable profile state lives in Tamework while
each summoned NPC is a temporary world projection.

## Required boundary

HyDragon requires Tamework `>=3.0.0 <4.0.0` and checks public API capability
names at runtime. Bonded features require both:

- advertised `BONDED_COMPANIONS`; and
- `TameworkApi.bondedCompanions().availability().available()`.

Draconic capture and dynamic encounter integration additionally require
`CAPTURE_POLICY`, `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`,
`INTERACTION_EXTENSIONS`, and `EVENTS`. Tamework diagnostic integration uses
`DIAGNOSTICS`.

The capability is refreshed at request time. If the bonded runtime is not
ready, dependent HyDragon actions show a specific blocker and fail before
taking a Stone, Soul Bond acquisition source, or revival cost.

## One shared Horn

The Dragon Horn command config uses:

```json
{
  "RosterStorage": "BondedCompanions",
  "BondedRosterId": "hydragon:dragon_horn",
  "MembershipMode": "LinkedOnly",
  "LinkEnabled": false,
  "LinkUseTogglesMembership": false
}
```

Full dragons and `Tamed_Wyvern_Mini` appear in this same panel. The Horn is an
access and command surface, not durable storage. Every card is keyed by the
stable bonded profile ID rather than an item-metadata row or live NPC UUID.

Two family policy assets share the roster:

- `hydragon:full_dragons`: capture-enabled, provision-disabled, unlimited
  owned, one active, 600-second session, 300-second cooldown;
- `hydragon:soulbound_mini`: capture-disabled, provision-enabled, one owned,
  one active, 900-second session, 180-second cooldown.

These are HyDragon's current data values. Tamework supports `0` session or
cooldown values when a family should be unlimited/no-cooldown.

## Lifecycle

Bonded companions have exactly three states:

- `STORED`: durable snapshot, no live projection;
- `ACTIVE`: one exact lease and matching projection; and
- `DEAD`: confirmed death, paid revival required.

Dismissal, expiration, logout, world transfer, missing projection, and
duplicate cleanup all become `STORED`. Bonded profiles never become generic
Unloaded, Lost, Captured, Cooped, roster-stored, or provisioned-dormant.

Revival returns `DEAD` to `STORED` and never summons automatically.

## Full-dragon capture

The Draconic Stone keeps its tranquilized-state requirement, channel behavior,
role mappings, capture probability, Horn access requirement, and resolved-
attempt consumption. It no longer requires a health threshold.

On success, `StoreBondedCompanion` durably creates one stored profile with the
complete NPC snapshot before retiring the source. No filled Stone, generic
command-family membership, generic population row, generic timed lease, or
generic profile is created. One completion effect is emitted after the durable
result.

The exact original source UUID is retained as profile-lifetime capture proof.
HyDragon listens for `BondedCompanionCaptureResolvedEvent` during normal play
and calls `BondedCompanionApi.findCapture` for restart recovery.

## Summon, store, and command behavior

Summon validates owner, roster/family, role, profile revision, cooldown,
family active capacity, snapshot, world, and safe placement. It creates one
lease token and one exact projection. Every summoned dragon or Miniwyvern
starts at full health, regardless of the health stored on its roster profile.

Dismiss/store captures the latest complete state before retiring that exact
projection. Automatic non-death cleanup follows the same stored convergence
rule. Normal Follow, Hold, Recall, Attack Target, and other command steps target
only the exact current-world NPC whose bonded marker matches the profile and
lease.

The panel renders name, species, gender, health, state, extension fields, and
buttons from the durable profile immediately after capture, summon, store,
revive, and relog. Live lookup can enrich volatile data but is not required for
a complete card.

## Death and revival

Only a positively confirmed death creates `DEAD`. A missing or unloaded
projection stores instead.

Current full-dragon revive recipe:

- 2 `Revitalizing_Essence`;
- 4 `Draconic_Essence`.

Current Miniwyvern revive recipe:

- 1 `Revitalizing_Essence`;
- 2 `Draconic_Essence`.

The panel quotes every line and reserves the complete recipe atomically. It
charges all lines once or refunds/contains the exact operation. Success changes
the same profile to `STORED`; the player summons separately.

## Miniwyvern Soul Bond and extension data

Soul Bond acquisition provisions one deterministic stored profile in family
`hydragon:soulbound_mini`. The one-lifetime rule is HyDragon policy; Tamework
provides idempotent profile/family admission and does not hardcode the Egg or
Soul Bond source.

Miniwyvern archetype, attunement, ability scheduler, and progression fields are
stored in owner/profile/namespace-qualified bonded extension data. HyDragon
uses revision-fenced compare-and-set updates. The extension survives summon,
store, logout, transfer, expiration, death, revive, and relog.

Ability runtime binds only to the exact active projection and detaches when the
profile becomes stored or dead. Detaching never deletes the extension.

## Encounter and flight eligibility

HyDragon lists `hydragon:dragon_horn` and accepts only a profile in family
`hydragon:full_dragons` whose state is `ACTIVE` and whose active lease is
present. Stored/dead full dragons, active Miniwyverns, stale projections, and
old generic population evidence do not qualify.

## Generic APIs remain available

HyDragon bonded profiles do not use `CommandFamilyRosterApi`,
`CommandTimedSummoningApi`, `CompanionProvisioningApi`,
`PaidCommandRevivalApi`, `PopulationGroupApi`, or generic `ProfileDataApi`.
Those APIs remain supported for ordinary Tamework integrations. Do not call a
generic API as a fallback for one bonded profile.

## Diagnostics and failure handling

Use:

```text
/tw debug persistence status
/tw debug persistence detail
/tw debug persistence export
```

The export includes a redacted `bonded-companions.json` member containing only
readiness, schema version, state counts, lease/cleanup counts, and a fixed
failure category. It excludes owners, profile IDs, NPC UUIDs, snapshots, and
extension payloads.

HyDragon should report its missing capability or bonded availability reason.
It must not infer readiness from a version string, diagnostic count, live NPC,
or old generic row.

## Validation scope

The integration is covered by public API, capability-off, bridge, capture,
Miniwyvern extension/ability, encounter, config, and packaged-asset tests. The
current docs do not claim exact Hytale `0.5.6` schema-profile validation because
that exact local profile is unavailable. Fresh-world gameplay acceptance and
final package alignment remain required before release preparation.

## Related pages

- [Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference)
- [TwBondedCompanionRosterConfig Reference](/mod/alecs-tamework/twbondedcompanionrosterconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
