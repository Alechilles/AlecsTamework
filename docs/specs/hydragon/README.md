# HyDragon Integration Specifications

Status: automated implementation complete; exact live acceptance pending

These specifications define Tamework's supported boundary for HyDragon 0.2.x
and Tamework 3.x:

- [Capture policy and resolved attempts](capture-policy.md)
- [Population groups and provisioning](population-groups.md)
- [Command rosters, timed summoning, capture, and revival](command-roster-capture-revival.md)
- [Cross-mod integration contract](integration-contract.md)
- [Recovery donor manifest](recovery-donor-manifest.md)

The restored Tamework runtime independently advertises each recovery capability
only after its complete persistence, recovery, projection, configuration, API,
UI, and integration slice is composed. The automated Tamework and HyDragon
gates are green; exact-artifact live acceptance remains required before release.

## Required capabilities

- `POPULATION_GROUPS`
- `COMPANION_PROVISIONING`
- `COMMAND_FAMILY_ROSTERS`
- `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`
- `CAPTURE_TAME_AND_LINK`
- `COMMAND_TIMED_SUMMONING`
- `PAID_COMMAND_REVIVAL`

These extend the already retained `PROFILES`, `PROFILE_DATA`, `POLICY`,
`PERSISTENCE_RESILIENCE`, and `CAPTURE_POLICY` authorities.

## Explicit exclusions

- `COMPANION_INVENTORY` remains deferred.
- `BONDED_VESSELS` remains removed.

The bonded Miniwyvern entitlement is not a bonded vessel. It is one
HyDragon-owned entitlement fulfilled through idempotent Tamework provisioning,
population admission, and Dragon Horn roster membership.

## Compatibility boundary

The features are unreleased, so old July schemas, DTO implementation details,
and tester saves are not compatibility contracts. Public Tamework `v2.16.1`
import remains supported. Testers use a public backup or fresh world when the
replacement schema changes.

The architecture and phased delivery authority is:

`C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs\Planned Features\Tamework\2026-07-24-required-persistence-feature-recovery-plan.md`
