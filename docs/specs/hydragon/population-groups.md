# Generic Population Groups and HyDragon Bonded Families

Status: HyDragon bonded population bindings superseded by the dedicated lease
model; generic Tamework population/provisioning systems retained

## Goal

Record the boundary between Tamework's general population-group authority and
the family policies used by HyDragon's ephemeral bonded companions. The two
systems solve different world-lifetime problems and must not both own one
profile.

## Generic population groups remain supported

`TwPopulationGroupConfig`, `PopulationGroupApi`, owner-population admission,
generic `CompanionProvisioningApi`, and their replacement-persistence tables
remain the authority for ordinary permanent-world and generic command-roster
companions.

They continue to support:

- role classification into zero or more groups;
- global or per-world owned/active limits;
- positive reservations for capacity-increasing operations;
- sealed reconciliation evidence;
- generic dormant provisioning and activation; and
- generic roster/timed-summon coordination.

No schema, API, or behavior in that system is deleted by the bonded lease
model.

## Why HyDragon no longer uses it

HyDragon full dragons and bonded Miniwyverns are not permanent world residents.
Their durable identity is a stored roster profile; any live NPC is disposable
and should be removed or stored whenever its lease is no longer valid.

Routing that model through generic profiles, population memberships,
command-family slots, timed-summon rows, lifecycle aliases, and outbox evidence
duplicated the same roster/state transition across several authorities. It also
introduced `UNLOADED`, `LOST`, and relog-dependent projection concerns that do
not belong to a temporary summon.

The dedicated bonded authority therefore owns these companions end to end. It
does not register them in generic population groups or ask generic population
evidence whether a bonded action is safe.

## Bonded family configuration

`TwBondedCompanionRosterConfig` defines family-scoped `MaximumOwned` and
`MaximumActive` values inside a shared bonded roster. Counts are derived from
bonded profiles and leases only.

HyDragon currently declares:

| Roster | Family | Maximum owned | Maximum active |
| --- | --- | ---: | ---: |
| `hydragon:dragon_horn` | `hydragon:full_dragons` | unlimited | 1 |
| `hydragon:dragon_horn` | `hydragon:soulbound_mini` | 1 | 1 |

The Miniwyvern's one-lifetime acquisition rule remains HyDragon policy. The
bonded family limit provides the durable roster admission constraint; it does
not hardcode Egg/Soul Bond semantics into Tamework.

Capacity is family-scoped. An active Miniwyvern does not consume the full-
dragon active slot, and an active full dragon does not consume the Miniwyvern
slot. Both cards still appear in the same Horn panel because they share the
roster ID.

## Bonded provisioning

Miniwyvern soul-bonding calls `BondedCompanionApi.provision` with:

- a stable HyDragon caller namespace and idempotency key;
- owner UUID;
- roster `hydragon:dragon_horn`;
- family `hydragon:soulbound_mini`;
- role `Tamed_Wyvern_Mini`; and
- initial durable presentation fields.

The operation creates one deterministic `STORED` profile. It creates no
generic `PROVISIONED_DORMANT` lifecycle, population classification,
command-family membership, timed lease, or live NPC. Miniwyvern extension data
is initialized separately through revision-fenced bonded extension data before
the acquisition is treated as complete.

A summon request later creates the first temporary projection. If projection
placement fails, the stored entitlement remains and another profile is not
minted.

## Capture admission

Full-dragon capture resolves the target's tamed role to exactly one bonded
family, then evaluates that family's owned limit and `Features.Capture`. It
does not reserve or increment a generic population group.

Concurrent capture/provision operations are serialized through bonded profile
and idempotency constraints so the final family slot cannot be over-admitted.
Ambiguous role-to-family mapping fails closed.

## Active eligibility

HyDragon's dynamic encounter/flight check no longer asks for an active count in
the former `hydragon:full_dragons` population group. It lists the owner's
bonded Horn profiles and requires one confirmed active profile in family
`hydragon:full_dragons` with a matching lease.

This avoids false eligibility from stored/dead profiles, active Miniwyverns,
stale generic population rows, or a live NPC that is not the canonical leased
projection.

## Retired HyDragon assets

The HyDragon-specific population assets for full dragons and the Soul Bond
Miniwyvern are retired after the reference audit confirms no non-bonded runtime
consumer. This does not retire `TwPopulationGroupConfig` or any Tamework
population asset/API generally.

The old `ActiveCompanionGroup` encounter setting is also removed because the
new eligibility resolver uses roster/family/profile/lease evidence directly.

## No migration contract

The bonded implementation targets fresh worlds. It does not inspect, migrate,
convert, delete, or repair old tester generic profiles, population rows,
command-family memberships, timed leases, or HyDragon lease experiments.

Existing generic data remains owned by the generic system. Operators should
use a fresh world for bonded acceptance rather than interpreting old rows as
new bonded profiles.

## Capability and failure behavior

HyDragon bonded acquisition and eligibility require `BONDED_COMPANIONS`, not
`POPULATION_GROUPS` or `COMPANION_PROVISIONING`. If bonded readiness is
unavailable, positive work is denied before resource consumption. There is no
fallback that creates generic population/provisioning state.

Generic integrations continue to require and use their existing population
and provisioning capabilities independently.

## Acceptance

- generic population and provisioning tests retain their prior behavior;
- no production HyDragon bonded path calls generic population/provisioning;
- full-dragon and Miniwyvern family limits are independent within one roster;
- concurrent final-slot requests cannot create a second profile;
- provisioning replay returns the same stored Miniwyvern;
- activation failure preserves the stored entitlement;
- active-full-dragon eligibility requires an exact active bonded lease;
- old HyDragon population assets have no remaining consumer; and
- no migration or generic fallback runs on a fresh-world implementation.
