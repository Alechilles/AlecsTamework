---
title: "Profile Data API Reference"
order: 4
published: true
draft: false
---
# Profile Data API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.8.0`)**
> This reference tracks the current `profileData()` contract in `TameworkApi`.

Capability: `PROFILE_DATA`

## Entry Point
`TameworkApi.profileData() -> ProfileDataApi`

## Methods
- `Optional<String> get(String profileId, String namespace, String key)`
- `Map<String, String> list(String profileId, String namespace)`
- `boolean put(String profileId, String namespace, String key, String jsonPayload)`
- `boolean delete(String profileId, String namespace, String key)`

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
- `put(...)` and `delete(...)` return `true` only when the write is accepted and committed.

## Recommended Namespace
Use your plugin id (for example `example.plugin`) as the namespace.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Store Per-Mob Plugin State JSON Recipe](/mod/alecs-tamework/store-per-mob-plugin-state-json-recipe)


