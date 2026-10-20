---
title: "HyDragon Integration Guide"
order: 8
published: true
draft: false
---
# HyDragon Integration Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

HyDragon uses Tamework's bonded companions for full dragons and bonded
Miniwyverns. Each one is an ordinary companion record in Tamework's companion
store, flagged as bonded and tied to a bonded roster. A summoned dragon is a body
in the world for as long as it is active; the record keeps its state while it is
stored or dead.

## Required boundary

Tamework 5.0 ships public API 3.0.0, which is not compatible with 2.x. Use a
HyDragon release built for Tamework 5.0. HyDragon also checks public API
capability names at runtime. Bonded features require both:

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
access and command surface, not storage. Every card is keyed by the stable
profile ID rather than an item-metadata row or live NPC UUID.

Two family policy assets share the roster:

- `hydragon:full_dragons`: capture-enabled, provision-disabled, unlimited
  owned, one active, 600-second session, 300-second cooldown;
- `hydragon:soulbound_mini`: capture-disabled, provision-enabled, one owned,
  one active, 900-second session, 180-second cooldown.

These are HyDragon's current data values. Tamework supports `0` session or
cooldown values when a family should be unlimited/no-cooldown.

## Lifecycle

Bonded companions have exactly three public states:

- `STORED`: saved snapshot, no body in a world;
- `ACTIVE`: summoned, with one body; and
- `DEAD`: confirmed death, revival required.

Dismissal, session expiry, owner logout and owner world change store the
companion. An owner's death does not. A body that vanished without a confirmed
death is listed as `STORED` and can be summoned again. Bonded companions never
appear as captured, cooped or lost in the ordinary panels, and the ordinary
command items and `/tw companions` commands refuse them.

Revival brings a `DEAD` companion back `ACTIVE` at the chosen place.

## Full-dragon capture

The Draconic Stone keeps its tranquilized-state requirement, channel behavior,
role mappings, capture probability, Horn access requirement, and resolved-
attempt consumption. It no longer requires a health threshold.

On success, `StoreBondedCompanion` saves one `STORED` bonded companion with the
complete NPC snapshot, then removes the body. No filled Stone is given. One
source item is spent before the save and given back when the save does not go
through. One completion effect is emitted after the save.

The original source NPC UUID is kept on the companion's record as capture
proof for as long as the companion exists. HyDragon listens for
`BondedCompanionCaptureResolvedEvent` during normal play and calls
`BondedCompanionApi.findCapture` after a restart.

## Summon, store, and command behavior

Summon checks owner, roster and family, role, expected revision, cooldown,
the family's active limit, the ordinary population limits, the snapshot, the
world and a safe placement. The active limit is checked again in the step that
saves the change. The record becomes `LIVE` with a new generation, and the body
is spawned from the snapshot. The lease token in the API is that generation as
text. Every summoned dragon or Miniwyvern starts at full health, regardless of
the health in its snapshot.

Store snapshots the body, saves the companion as `STORED` with a new
generation, then removes the body. A copy of the body left in an unloaded chunk
is removed by Tamework's generation fence when it loads. Normal Follow, Hold,
Recall, Attack Target, and other command steps target only the current body of
the active companion.

The panel renders name, species, health, state, extension fields, and buttons
from the companion's record right after capture, summon, store, revive, and
relog. Live lookup can enrich volatile data but is not required for a complete
card.

## Death and revival

Only a confirmed death creates `DEAD`. A body that vanished or unloaded
does not.

Current full-dragon revive recipe:

- 2 `Revitalizing_Essence`;
- 4 `Draconic_Essence`.

Current Miniwyvern revive recipe:

- 1 `Revitalizing_Essence`;
- 2 `Draconic_Essence`.

The panel quotes every line and takes the complete recipe at once. If the
revive does not bring the companion back, the items are refunded. A revive
needs a free active place in the family, because the companion comes back
`ACTIVE` at the chosen place with a new session.

## Miniwyvern Soul Bond and extension data

Soul Bond acquisition provisions one stored companion in family
`hydragon:soulbound_mini`. The one-lifetime rule is HyDragon policy. Tamework
treats a repeat of the same caller namespace and idempotency key as the same
request, checks the family and population limits, and does not hardcode the Egg
or Soul Bond source. Provisioned companions count toward the owned limit.

Miniwyvern archetype, attunement, ability scheduler, and progression fields are
stored as bonded extension data on the companion's record, one value per
namespace. HyDragon uses compare-and-set updates on the value's revision. The
value survives summon, store, logout, world change, expiry, death, revive, and
relog, and goes away only when the companion is abandoned.

Ability runtime binds only to the current body of the active companion and
detaches when the companion is stored or dead. Detaching never deletes the
extension data.

## Encounter and flight eligibility

HyDragon lists `hydragon:dragon_horn` and accepts only a companion in family
`hydragon:full_dragons` whose state is `ACTIVE` and that has a lease. Stored or
dead full dragons, active Miniwyverns, and stale bodies do not qualify.

## Generic APIs

Use `BondedCompanionApi` for bonded companions. Do not call a generic API, such
as `ProfileDataApi`, as a fallback for one bonded companion. API 3.0.0 removed
the generic command-family roster, timed summoning, companion provisioning and
paid revival APIs; see the
[Public API Overview](/mod/alecs-tamework/public-api-overview).

## Diagnostics and failure handling

Tamework 5.0 has no persistence debug commands; `/tw debug persistence` and
all its subcommands were removed. Use the server log to diagnose a bonded
companion problem. For a world updated from 3.x or 4.x, the import
report described in
[World Migration for Server Admins](/mod/alecs-tamework/world-migration-for-server-admins)
lists the bonded companions that were imported.

HyDragon should report its missing capability or bonded availability reason.
It must not infer readiness from a version string, diagnostic count, or live
NPC.

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
