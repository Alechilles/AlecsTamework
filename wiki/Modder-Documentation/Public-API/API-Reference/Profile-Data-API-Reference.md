---
title: "Profile Data API Reference"
order: 4
published: true
draft: false
---
# Profile Data API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> This reference tracks the current `profileData()` contract in `TameworkApi`.

Capabilities: `PROFILE_DATA` for legacy reads/writes and
`PROFILE_DATA_TRANSACTIONS` for revision-fenced, restart-visible mutations.

Tamework 3.0.0 advertises `PROFILE_DATA`, but does not currently advertise
`PROFILE_DATA_TRANSACTIONS`. The transactional methods below are a fail-closed
binary-compatible contract until their durable repository is wired.

## Entry Point
`TameworkApi.profileData() -> ProfileDataApi`

## Methods
- `Optional<String> get(String profileId, String namespace, String key)`
- `Map<String, String> list(String profileId, String namespace)`
- `boolean put(String profileId, String namespace, String key, String jsonPayload)`
- `boolean delete(String profileId, String namespace, String key)`

The legacy `put`/`delete` methods remain for API compatibility, but queue
acceptance is not a durable cross-mod transaction result. Integrations that
must coordinate material consumption, cooldowns, entitlement, or another
durable domain should require `PROFILE_DATA_TRANSACTIONS` and use the methods
below.

## Transactional methods

- `Optional<ProfileDataEntryView> getVersioned(String profileId, String namespace, String key)`
- `CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(ProfileDataCompareAndSetRequest request)`
- `CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(String profileId, String namespace, String key, long expectedRevision, String idempotencyKey, String jsonPayload)`
- `CompletionStage<Optional<ProfileDataOperationView>> findOperation(String namespace, String idempotencyKey)`

An existing value starts at revision `1`. Expected revision `0`
(`MISSING_REVISION`) means the key must not exist. A committed compare-and-set
publishes exactly `expectedRevision + 1`.

The namespace plus idempotency key is the durable operation origin. Reuse it
for the same logical mutation across callbacks, timeouts, or restart. A
conflicting retry does not overwrite the original operation.

## Durable outcomes

`ProfileDataCompareAndSetResult` reports:

- `COMMITTED`: includes the matching committed operation and revisioned entry.
- `TERMINAL_DENIED`: includes durable denial and no entry.
- `QUARANTINED`: includes durable ambiguous/fenced state and no entry.
- `UNAVAILABLE`: claims no durable result.

`findOperation` can expose `PREPARED`, `APPLYING`, `COMMITTED`,
`TERMINAL_DENIED`, or `QUARANTINED`. Unknown, unavailable, or nonterminal state
never authorizes a caller to invent a second idempotency key or compensate as
though the mutation failed.

The API 0.8-compatible defaults are deliberately empty/unavailable. Require
`PROFILE_DATA_TRANSACTIONS` before calling them.

## Data Model
Profile-scoped extension data is stored as UTF-8 JSON text keyed by:
- `profileId`
- `namespace`
- `key`

## Rules
- `namespace` and `key` must be nonblank.
- `jsonPayload` must parse as JSON text.
- `Alechilles:Tamework` is reserved for internal use.
- Writes go through Tamework's persistence write queue.
- `put(...)` and `delete(...)` returning `true` proves legacy queue acceptance,
  not a restart-visible cross-domain commit.

## Recommended Namespace
Use your plugin id (for example `example.plugin`) as the namespace.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Store Per-Mob Plugin State JSON Recipe](/mod/alecs-tamework/store-per-mob-plugin-state-json-recipe)


