---
title: "Admission Providers API Reference"
order: 15
published: true
draft: false
---
# Admission Providers API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> Providers replace the removed `policies().populationAdmissions()`
> reservations and the `NAMED_CAPACITY_RESERVATIONS` capability.

Capabilities: `EXTERNAL_ADMISSION_PROVIDERS` and `REQUIRED_CONTENT_PROFILES`.

Entry points: `TameworkApi.policies().admissionProviders()` and
`TameworkApi.requiredContentProfiles()`.

An admission provider lets another mod decide whether a player may get, or
deploy, a companion of a managed role. A role is managed when a managed
activity profile lists it. The profile names the provider ID and contract
version that must answer for its roles.

## Registration

```java
AutoCloseable handle = api.policies().admissionProviders()
        .register("example:husbandry", 1, request -> decide(request));
```

- `providerId` is trimmed and lower-cased. One provider per ID. A second
  registration under the same ID throws `IllegalStateException`.
- `contractVersion` must be positive and must equal the version the managed
  profile asks for.
- Closing the handle stops new evaluations. One already in flight may finish.
- Close the handle when your plugin unloads.

`requiredContentProfiles().status(profileId)` reports whether a managed profile
is ready: its config is loaded and usable, and its provider is registered with
the right contract version. `detail` says why it is not.

## When Tamework asks

Tamework asks only when a change gives a companion an owner it did not count
for, or deploys it (`LIVE`) when it was not deployed. Moves that add nothing,
such as storing a companion, are not put to a provider.

Tamework fails closed. A managed role is refused when:

- no provider is registered under the profile's ID;
- the registered contract version differs;
- the managed config is stale (it no longer matches the population groups);
- the provider throws, returns null, completes exceptionally, or takes longer
  than 2 seconds;
- the provider's work queue is full; or
- an `ALLOW` names a `configRevision` other than the one the server has
  loaded.

All of these show the player `tamework.ui.population.providerUnavailable` and
change nothing. An unregistered provider is logged once as a warning.

There is no admin bypass. Every request has force policy `ENFORCE`, and admin
tamed spawns of managed roles are asked like any other.

## The decision

Return a `PopulationAdmissionProviderDecision`:

| Field | Rule |
| --- | --- |
| `status` | `ALLOW`, `DENY`, or `UNAVAILABLE`. |
| `messageKey` | Required. For `DENY` it is the translation key shown to the player. |
| `claims` | The `PopulationDomainClaim`s the companion will hold. |
| `domainLimits` | The owner's limit per claimed domain ID. |
| `configRevision` | Must equal `request.managedConfigRevision()` for an `ALLOW`. |

Claim and limit rules:

- Every claim needs a limit entry for its domain. An `ALLOW` with a claim that
  has no limit is refused as unavailable.
- A domain may be claimed once as owned and once as deployable. A duplicate is
  refused as unavailable.
- A limit of `0` admits nothing. This differs from the built-in group limits,
  where `0` means no limit.
- A claim counts by its `weight`. An owned claim counts on every companion of
  the owner that is not released. A deployable claim counts only on `LIVE`
  companions.
- Tamework stores the claims on the companion and counts them itself, in the
  same step that saves the change. A change is refused when the claim's weight
  plus the owner's other claims in that domain would pass the limit.
- A refused domain limit shows `tamework.ui.population.ownedLimit` or
  `tamework.ui.population.deployedLimit`.
- Domain counts are per owner across all worlds.

Companions that became owned before your provider registered hold no claims.
They do not count toward your domain limits until a later change asks the
provider about them.

### Deny message keys

Tamework shows the `DENY` decision's `messageKey` to the player. Ship that key
in your own language files. When Tamework cannot resolve the key it falls back
to its own text, `tamework.ui.population.providerDenied`.

## Two kinds of request

### Exact requests

Restores of every kind (for example summon, recall, revive, and release from
an item) and captures are already asynchronous. For these Tamework asks the
provider about the exact change before it saves, and does not cache the
answer. The request carries the real operation, old owner, new owner, and
locations.

A companion with no body (held in an item or stored) that changes owner is
described as a new companion of the new owner, with operation `NEW_OWNERSHIP`.

### Cached requests

Taming, a capture item changing holder, item pickup, and litters run
synchronously on a world thread and cannot wait. For these Tamework uses a
cached decision per owner and managed family.

- The request always has the same shape: operation `NEW_OWNERSHIP`, no old
  owner, and a destination that names the world with chunk `0,0`. Do not base
  a decision on the operation, old owner, or chunk of such a request.
- An `ALLOW` or `DENY` is reused for up to 30 seconds. An unavailable answer is
  reused for 5 seconds.
- The owner's cached decisions are dropped when the owner's counts change,
  when the owner disconnects, on a config reload, and when a provider
  registers or unregisters.
- Decisions are fetched when a player joins. With no cached decision the
  action is refused once with `tamework.ui.population.checkingRequirements`
  and the evaluation starts. The player can try again a moment later.

Within the reuse window Tamework holds an allowed owner only to the claims and
limits of the cached decision. A provider that counts companions on its own
and returns no claims can be overshot in that window. Return claims and limits
so Tamework can enforce them.

## Threading

`evaluate` runs on Tamework's provider threads (at most 4 at once, with 8
waiting). It has no world-thread guarantee and may run for several requests at
once, in any order. Implementations must be thread-safe, must not block, and
must decide only from the request and their own policy snapshot. They must not
read or change live entities.

## Request types

`PopulationAdmissionProviderRequest` carries `providerId`, `contractVersion`,
`familyGroupId`, `groupIds`, `gateKey`, `weight`, `managedConfigRevision`, and
`admission`. Unwrap the base request with
`request.admission().request().request()`:

- `PopulationAdmissionRequestV3`: `request`, `managedProfileId`
- `PopulationAdmissionRequestV2`: `request`, role ID, destination world
- `PopulationAdmissionRequest`: identity, NPC UUID, old and new owner,
  locations, operation, slots, force policy, lifecycle

These request types, `PopulationAdmissionIdentity`,
`PopulationAdmissionOperation`, `PopulationCompanionLifecycle`,
`PopulationAdmissionLocation`, and `PopulationAdmissionForcePolicy` are kept
as stable public types.

## Related Pages
- [Policies API Reference](/mod/alecs-tamework/policies-api-reference)
- [Population Groups API Reference](/mod/alecs-tamework/population-groups-api-reference)
- [Events API Reference](/mod/alecs-tamework/events-api-reference)
