# HyDragon Integration Specifications

Status: bonded implementation and focused automated coverage complete; clean
package verification and fresh-world acceptance pending

These specifications define Tamework's supported boundary for HyDragon 0.2.x
and Tamework 3.x:

- [Capture policy and resolved attempts](capture-policy.md)
- [Generic population groups and bonded families](population-groups.md)
- [Bonded Horn roster, capture, leases, and revival](command-roster-capture-revival.md)
- [Cross-mod integration contract](integration-contract.md)
- [Recovery donor manifest](recovery-donor-manifest.md)

Tamework advertises `BONDED_COMPANIONS` only after the separate bonded database
and runtime are ready. HyDragon refreshes both capability and bonded
availability at request time. Generic persistence capabilities remain
independently advertised for their ordinary consumers.

## Required capabilities

- `BONDED_COMPANIONS`
- `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`
- `CAPTURE_POLICY`
- `INTERACTION_EXTENSIONS`
- `EVENTS`

Only capture/encounter consumers require the capture and event capabilities.
Horn, summon/store, revival, Soul Bond, Miniwyvern state, and active-dragon
eligibility use the dedicated bonded API. They do not fall back to generic
population, provisioning, command-family, timed-summon, paid-revive, or
profile-data APIs.

## Explicit exclusions

- `COMPANION_INVENTORY` remains deferred.
- `BONDED_VESSELS` remains removed.

The bonded Miniwyvern entitlement is not a bonded vessel. HyDragon requests
one idempotent `STORED` profile in the shared Horn's Miniwyvern family and
stores its domain state in namespaced bonded extension data.

## Compatibility boundary

The feature is unreleased, so old tester generic roster, population,
timed-summon, or lease data is not a migration contract. Bonded acceptance uses
a fresh world and does not inspect, convert, repair, or delete those old rows.
Public Tamework `v2.16.1` import remains a separate generic-persistence concern.

The architecture and phased delivery authority is:

`C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs\Planned Features\2026-07-25-bonded-companion-lease-model-implementation-plan.md`
