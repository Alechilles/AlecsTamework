---
title: "Timed Summoning API Reference"
order: 16
published: true
draft: false
---
# Timed Summoning API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Capability: `COMMAND_TIMED_SUMMONING`

Entry point: `TameworkApi.commandTimedSummoning()`.

## Methods

- `get(identity)`
- `summon(request)`
- `dismiss(request)`
- `subscribe(listener)`

The request identifies one owner, command family, and canonical profile. The
authority coordinates roster membership, active population/group admission,
canonical lifecycle, the exact lease, and live projection evidence.

`summon` activates a roster-stored profile and starts or resumes its authored
lease. `dismiss` durably retires the source projection before committing
`ROSTER_STORED` and releasing active capacity. Expiry and configured owner
logout storage use the same transition. Negative world-time timestamps are
valid; `0` is the only unset sentinel.

Always close the `subscribe` handle. Missing timed-summon capability must deny
a new projection without deleting roster membership.

## Bonded companions are separate

This API remains the timed-lease authority for generic owner/command-family
rosters. It is not used for bonded profiles.

Bonded session duration and summon cooldown live in
`TwBondedCompanionRosterConfig`; `BondedCompanionApi.summon` and `store` own
the corresponding projection lease. A zero duration is unlimited, a positive
duration expires to `STORED`, and every non-death disappearance also converges
to `STORED`. Bonded leases do not create generic timed-summon rows or generic
lifecycle aliases.

See [Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference).
