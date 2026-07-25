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
