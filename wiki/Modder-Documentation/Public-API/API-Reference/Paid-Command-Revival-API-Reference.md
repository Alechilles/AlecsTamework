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
