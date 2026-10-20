---
title: "Profile Data API Reference"
order: 4
published: true
draft: false
---
# Profile Data API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> `findOperation` was removed. There is no stored operation log. A namespace
> may no longer contain `/`.

Capabilities: `PROFILE_DATA` for basic reads and writes and
`PROFILE_DATA_TRANSACTIONS` for revision-checked writes. Both are advertised
in 3.0.0.

## Entry Point
`TameworkApi.profileData() -> ProfileDataApi`

## Data Model
A value is JSON text stored on the companion's record under `profileId`,
`namespace`, and `key`. Each value has its own revision.

## Rules
- `namespace` and `key` must be nonblank. Both are trimmed. A namespace has at
  most 128 characters and a key at most 256.
- `namespace` may not contain `/`. A key may.
- `tamework` and `Alechilles:Tamework` are reserved, in any letter case.
- `jsonPayload` must be valid JSON of at most 1,048,576 characters. It is
  stored in canonical form.
- A reserved or invalid namespace, an unparsable profile ID, and a released
  companion read as "no profile data" and refuse writes.

Use your plugin ID (for example `example.plugin`) as the namespace.

## Simple methods
- `Optional<String> get(String profileId, String namespace, String key)`
- `Map<String, String> list(String profileId, String namespace)`
- `boolean put(String profileId, String namespace, String key, String jsonPayload)`
- `boolean delete(String profileId, String namespace, String key)`

Reads are synchronous and safe from any thread.

`put` and `delete` change the record in memory and return at once. The owner's
file is written on the writer's next flush. `true` means the change was
applied in memory, not that it is on disk. `delete` returns `true` when the
profile exists and no longer has the value, including when it never had it.

## Revision-checked methods

- `Optional<ProfileDataEntryView> getVersioned(String profileId, String namespace, String key)`
- `CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(ProfileDataCompareAndSetRequest request)`
- `CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(String profileId, String namespace, String key, long expectedRevision, String idempotencyKey, String jsonPayload)`

Revisions:

- The first value has revision `1`.
- Expected revision `0` means the key must not exist.
- A committed write has revision `expectedRevision + 1`.
- A `put` also adds one to the revision.

`compareAndSet` checks the revision and changes the value in one step. The
stage completes only after the owner's file is written. When that write fails
the change is undone.

The stage may complete on a thread that is not a world thread (the store's
writer thread). A continuation must not block and must not read or change
entities, components, or worlds. Hop to the owning world with
`world.execute(...)` first.

### Results

| Status | Reason | Meaning |
| --- | --- | --- |
| `COMMITTED` | `profile-data-committed` | Written to disk. The result has the operation and the entry. |
| `TERMINAL_DENIED` | `profile-data-revision-mismatch` | The value's revision is not the expected one. Read again and retry. |
| `TERMINAL_DENIED` | `profile-data-profile-not-found` | No such companion, or it was released. |
| `TERMINAL_DENIED` | `profile-data-namespace-refused` | Reserved namespace, or a namespace with `/`. |
| `UNAVAILABLE` | `profile-data-flush-failed` | The file write failed. The change was undone. The result has no operation. |

`QUARANTINED` is not returned in 3.0.0.

### Repeated requests

The `ProfileDataOperationView` in a result is built from the profile, key, and
revision. It is not read from a log, and `findOperation` no longer exists.

Repeating a request while its value and revision are still the current ones
returns `COMMITTED` again, after the same wait for the file. After a later
change the same request returns a revision mismatch. To find out what happened
after a restart, call `getVersioned` and compare the revision and payload.

## Bonded extension data

Bonded companions use `BondedCompanionApi.getExtensionData` and
`compareAndSetExtensionData`. The namespace rules are the same. The public
revision there starts at `0`, not `1`. See
[Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference).

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Store Per-Mob Plugin State JSON Recipe](/mod/alecs-tamework/store-per-mob-plugin-state-json-recipe)
