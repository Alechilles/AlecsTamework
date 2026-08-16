# Bonded Horn Roster, Capture, Leases, and Revival

Status: implementation and automated contract coverage complete; clean package
verification and fresh-world acceptance pending

## Goal

Use one durable Dragon Horn roster for full dragons and bonded Miniwyverns while
treating every live NPC as a temporary projection. Preserve complete gameplay
state across capture, summon, dismissal, logout, world transfer, expiration,
recovery, death, revival, and relog without sending these companions through
Tamework's permanent-world persistence machinery.

## Authority boundary

HyDragon bonded companions use `BondedCompanionApi` and the independent
`bonded-companions.sqlite` database. They do not create or depend on:

- generic canonical/dormant profiles or lifecycle aliases;
- owner/command-family roster memberships;
- generic population-group evidence or reservations;
- generic timed-summon leases;
- generic paid-revival operations;
- generic profile extension rows; or
- replacement-persistence outbox/readiness evidence.

The generic APIs and persistence systems remain unchanged for ordinary
companions and other integrations. Only HyDragon's old bindings to those
generic surfaces are superseded.

## Shared Horn roster

The Horn is configured with:

```text
CommandConfigId = HyDragonDragonHorn
RosterStorage = BondedCompanions
BondedRosterId = hydragon:dragon_horn
MembershipMode = LinkedOnly
LinkEnabled = false
LinkUseTogglesMembership = false
RequireOwner = true
RequireTamed = true
```

It is an access, panel, and live-command surface. It is not the durable roster
and does not project roster state into item metadata. Multiple Horn copies for
one owner resolve the same profile set.

Two policy families share that roster:

| Family | Roles | Max owned | Max active | Session | Cooldown |
| --- | --- | ---: | ---: | ---: | ---: |
| `hydragon:full_dragons` | tamed Nordic Drake, Hydra, and Rock Drake tiers | unlimited | 1 | 600 s | 300 s |
| `hydragon:soulbound_mini` | `Tamed_Wyvern_Mini` | 1 | 1 | 900 s | 180 s |

These are declarative current HyDragon values, not hardcoded Tamework rules.
Either timer can be configured as `0` to disable it. Each profile retains its
family ID so policy never depends on guessing from a live NPC.

## Profile lifecycle

The player-visible lifecycle is exactly:

```text
STORED --summon--> ACTIVE --non-death exit--> STORED
                       |
                  confirmed death
                       v
                      DEAD --paid revive--> STORED
```

There is no bonded `UNLOADED`, `LOST`, `CAPTURED`, `COOPED`,
`ROSTER_STORED`, or `PROVISIONED_DORMANT`. Missing, stale, expired, duplicate,
logout, and transfer cases converge to `STORED`. Only a positively observed
death creates `DEAD`.

The stable bonded profile ID is roster and UI identity. An active lease carries
an opaque lease token, exact live NPC UUID, world key, start, and optional
expiry only so Tamework can validate and retire that projection.

## Full snapshot rule

Capture stores the complete snapshot before retiring the source. Storing an
active projection snapshots it before removal and merges state without
deleting unavailable optional components.

The snapshot retains all supported gameplay data: role/appearance inputs,
owner and tamed state, custom name, health, needs, happiness, breeding,
progression, traits, talents, life stage, attachments, command settings, and
HyDragon namespaced extension data. The panel renders from this durable state
immediately; relog is not a data-loading prerequisite.

Every summon gives the new live projection full health. Stored health remains
available for durable presentation, but it does not reduce health on the next
live projection.

## Draconic Stone capture

HyDragon's Draconic Stone uses:

```text
SuccessDisposition = StoreBondedCompanion
BondedRosterId = hydragon:dragon_horn
RequiredCommandConfigId = HyDragonDragonHorn
RequireCommandAccessItem = true
RequiredEffectId = Tw_Status_Tranquilized
SourceConsumption = ResolvedAttempt
```

The current capture deliberately requires tranquilized state and does not
require a health threshold. Wild source roles map to their corresponding tamed
roles before bonded-family admission.

One durable capture operation owns the resolved roll, source-item spend,
complete snapshot, stable profile, exact source identity, cleanup intent, and
result. On success the profile is `STORED`, the source NPC is retired, one
completion effect is emitted, and no filled Stone is created. On a terminal
failed probability roll, `ResolvedAttempt` spends the Stone once and applies
the configured failure result. Denial before the roll spends nothing.

The profile-lifetime source record prevents the same original NPC from being
captured into a second profile after operation-history retention. Live capture
events are convenience notifications; restart recovery uses
`BondedCompanionApi.findCapture`.

## Summon

Summon validates owner, roster, family, role, profile revision, `STORED` state,
snapshot, feature toggle, cooldown, family active capacity, destination world,
and safe placement. It then creates one lease and one exact projection.

A positive session duration produces a signed expiry deadline. `0` is the only
unlimited sentinel; negative world-time values remain valid timestamps. A
failed or replayed request cannot create a second lease or projection.

## Store, expiry, logout, and world transfer

Explicit Dismiss, session expiry, logout, world transfer, missing-projection
recovery, and duplicate cleanup share the same convergence rule:

1. validate the exact profile, lease token, live UUID, and world;
2. capture and durably merge the latest complete state when the projection is
   available;
3. retire the exact projection or retain a bounded cleanup intent;
4. clear the lease and commit `STORED`; and
5. begin the family's configured summon cooldown.

The system never manufactures Lost or leaves an unloaded alias for these
events. Duplicate cleanup cannot turn an unselected copy into canonical state.

## Death

The death system accepts only an exact matching bonded marker and active lease.
It captures the final state, retires the lease, and commits `DEAD`. An ordinary
unload/remove observation without positive death evidence stores the profile
instead.

Death does not erase the profile, family, Horn card, snapshot, or Miniwyvern
extension. A dead profile cannot be summoned or dismissed.

## Paid revival

The full-dragon family currently requires:

- 2 `Revitalizing_Essence`; and
- 4 `Draconic_Essence`.

The Miniwyvern family currently requires:

- 1 `Revitalizing_Essence`; and
- 2 `Draconic_Essence`.

Each recipe is an ordered AND-list. The panel shows every owned/required line,
preflights the whole recipe, and reserves it as one atomic escrow operation.
The durable profile transition either consumes the complete recipe once or
restores the exact escrow. Interrupted settlement is recovered from durable
operation evidence and never guessed from inventory absence.

Successful revival changes the same profile from `DEAD` to `STORED`. It does
not spawn. The player explicitly summons after revival and after any applicable
summon cooldown.

## Profile-first panel

Every Horn card is keyed by the stable bonded profile ID and built from
`BondedCompanionProfileView`. It shows full name/species/gender, state, health
and other snapshot fields, family extension presentation, availability reason,
and state-appropriate actions immediately after capture, summon, store,
revive, duplicate cleanup, and relog.

An exact active projection may enrich volatile health, but missing live lookup
cannot collapse a valid card to name-and-health-only or remove its buttons.
Actions carry profile ID and expected revision; they never route through a live
NPC UUID or generic link row.

## Public capability behavior

HyDragon requires `BONDED_COMPANIONS` for its Horn roster, capture storage,
Miniwyvern provisioning/extension state, summon/store, revival, abilities, and
active-full-dragon eligibility. Capture probability continues to require
`CAPTURE_POLICY` and its resolved-attempt contract.

If the bonded capability or its own availability is missing, dependent actions
fail before source spend or payment. HyDragon does not fall back to
`CommandFamilyRosterApi`, `CommandTimedSummoningApi`,
`CompanionProvisioningApi`, `PaidCommandRevivalApi`, `PopulationGroupApi`, or
`ProfileDataApi` for a bonded profile.

## Acceptance

- full dragons and Miniwyverns appear in the same Horn;
- one profile owns at most one active lease/projection;
- cards remain complete through every transition without relog;
- capture is durable before source cleanup and cannot partially succeed;
- every non-death disappearance becomes `STORED`;
- only confirmed death becomes `DEAD`;
- revival charges the complete family recipe once and returns to `STORED`;
- finite sessions expire while `0` duration never expires;
- negative world timestamps are preserved;
- generic permanent-animal and command-roster behavior remains unchanged; and
- no bonded-vessel item state or HyDragon-specific fallback database returns.
