---
title: "Paid Command Revival API Reference"
order: 18
published: true
draft: false
---
# Paid Command Revival API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Capability: `PAID_COMMAND_REVIVAL`

Entry point: `TameworkApi.paidCommandRevival()`.

## Methods

- `quote(request)`
- `revive(request)`
- `findOperation(callerNamespace, idempotencyKey)`

`quote` returns the authoritative status, gameplay cooldown, exact ordered AND
item recipe, config revision, and reason for one owner/family/profile. Display
that quote before confirmation, but expect `revive` to recheck admission,
recipe, lifecycle, and revision.

`revive` charges the complete recipe and restores the same canonical Dead or
Lost profile once. Denial, shortage, cooldown, or unavailable authority charges
nothing. A proven terminal failure after an exact charge creates one durable
exact refund claim; ambiguous evidence fails closed.

Use a stable caller namespace and idempotency key, and recover uncertain
results through `findOperation`. Do not retry with a new key or implement an
external charge/refund journal.

## Bonded companions are separate

This generic surface continues to revive ordinary Dead or Lost canonical
profiles in owner/command-family rosters. It is not used for a bonded profile.

Bonded revival is exposed by `BondedCompanionApi.quoteRevive` and
`BondedCompanionApi.revive` under `BONDED_COMPANIONS`. Its recipe belongs to
the bonded roster family, every configured cost is reserved as one atomic
batch, and success changes `DEAD` to `STORED` without summoning. The normal
bonded panel supplies the required live inventory/escrow context.

Do not recover a failed bonded revive through `PaidCommandRevivalApi` or turn a
bonded `DEAD` profile into generic Lost/dead lifecycle state.

See [Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference).
