---
title: "Admission Providers API Reference"
order: 15
published: true
draft: false
---
# Admission Providers API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `4.0.0`**
> Providers answer synchronously through
> `SynchronousPopulationAdmissionProvider`. The asynchronous
> `PopulationAdmissionProvider` of API 3.x is removed.

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

`decide` returns a `PopulationAdmissionProviderDecision` at once. See
"Threading" below for what it may and may not do.

- `providerId` is trimmed and lower-cased. One provider per ID. A second
  registration under the same ID throws `IllegalStateException`.
- `contractVersion` must be positive and must equal the version the managed
  profile asks for.
- Closing the handle stops new calls. One already running finishes.
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
- the provider throws or returns null; or
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

## What the request describes

Tamework asks about each change when it happens, and does not cache answers.
The request carries the operation, old owner, new owner, and locations of the
change: a tame, a restore (summon, recall, revive, release from an item), a
capture, a capture item changing holder, an item pickup, a litter, or a spawn.

- A companion with no body (held in an item or stored) that changes owner is
  described as a new companion of the new owner, with operation
  `NEW_OWNERSHIP`.
- A pre-check before the companion exists (before a tame spends food, or
  before a spawn) describes a new companion of the owner with a random
  profile ID at chunk `0,0` of the world. The binding check that follows
  describes the real companion.

Tamework counts the claims of an `ALLOW` itself, in the step that saves the
change. A provider that counts companions on its own and returns no claims can
be overshot by two changes decided at the same time. Return claims and limits
so Tamework can enforce them.

## Threading

`decide` runs inline wherever Tamework needs an answer: on a world thread
during a tame, pickup, holder change, litter or spawn, on other threads during
captures and restores, and sometimes while Tamework holds its companion index
lock. It may run for several requests at once, in any order.

- Answer from memory. Do not block, wait on a future, do I/O, enter a world
  thread, or call Tamework APIs.
- Be thread-safe. Do not read or change live entities.
- When your state for the owner is not ready yet (for example a profile still
  loading after join), return `UNAVAILABLE`.

Tamework cannot interrupt a call. It logs a warning, at most once a minute per
provider, when a call takes longer than 5 ms.

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
