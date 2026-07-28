---
title: "Companion Provisioning API Reference"
order: 17
published: true
draft: false
---
# Companion Provisioning API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Capability: `COMPANION_PROVISIONING`

Entry point: `TameworkApi.companionProvisioning()`.

## Methods

- `getByProfileId(profileId)`
- `getByOrigin(callerNamespace, idempotencyKey)`
- `provision(request)`
- `provisionAndLink(request)`
- `transition(request)`
- `findOperation(callerNamespace, idempotencyKey)`

Provisioning creates one deterministic entitlement and canonical profile from
the caller namespace/idempotency key. `provisionAndLink` atomically creates the
dormant profile, population classification, and owner/family roster
membership. A separately recoverable activation may create the first live
projection.

Repeated equivalent requests return the same profile/result. Do not generate a
second local entitlement, physical bonded-vessel state, or private
provisioning journal. `findOperation` is the restart recovery surface.

Provisioned death/revival preserves the entitlement. A dormant revival can
return to `PROVISIONED_DORMANT` without spawning until activation is requested.

## Bonded companions are separate

This generic API remains the supported authority for ordinary provisioned
companions and generic command-family integration. It does not provision a
profile in the bonded lease-model database.

For an ephemeral bonded roster profile, require `BONDED_COMPANIONS` and call
`BondedCompanionApi.provision`. That operation creates a `STORED` bonded
profile directly, uses the bonded roster/family policy, and does not create a
generic dormant lifecycle, population-group membership, command-family row,
or timed-summon lease. Do not call both provisioning APIs for one entitlement.

See [Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference).
