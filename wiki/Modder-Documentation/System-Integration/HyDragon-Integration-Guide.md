---
title: "HyDragon Integration Guide"
order: 8
published: true
draft: false
---
# HyDragon Integration Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

HyDragon should integrate through Tamework's general public surfaces rather
than private persistence code.

## Supported boundaries

- Canonical companion profile IDs are the cross-mod identity.
- `ProfileDataApi` stores HyDragon-owned namespaced data. Require
  `PROFILE_DATA_TRANSACTIONS` for revision-fenced compare-and-set and durable
  operation lookup.
- `PopulationGroupApi` supplies role classification, owned/active counts, and
  reconciliation status.
- `CommandFamilyRosterApi` is the durable owner/family/profile roster
  authority. A Dragon Horn item is a view and command surface, not the roster
  database.
- `CommandTimedSummoningApi` owns summon, dismiss/storage, expiry, warning,
  logout, and cooldown lease state.
- `CompanionProvisioningApi` idempotently creates and links dormant
  entitlements such as the bonded Miniwyvern, then activates the same profile
  through a separately recoverable projection.
- `PaidCommandRevivalApi` provides the exact quote, same-profile revival, and
  restart-visible operation lookup.
- Capture-policy configs and handlers supply generic probabilistic capture,
  resolved-attempt consumption, and in-place tame/link behavior without adding
  species-specific persistence to Tamework.
- Existing command links, progression, config reads, events, and interaction
  extensions remain available through their advertised capabilities.

HyDragon must not write Tamework SQLite rows or treat a live entity UUID as the
companion's durable identity.

Draconic capture uses the terminal resolved-attempt contract. The configured
source is spent exactly once after either terminal success or failure. A
successful tame/link attempt preserves the live target, establishes its stable
profile/owner/role, adds one Horn roster membership, classifies population
groups, and starts one timed lease atomically. Denial occurs before entropy or
source spend.

## Companion lifecycle

Tamework's canonical lifecycle covers active, unloaded, captured, cooped,
roster-stored, provisioned-dormant, dead, Lost, released, and unresolved
companions. HyDragon does not keep a second lifecycle.

- Full dragons remain roster members while timed storage retires their live
  projection and releases active capacity.
- A bonded Miniwyvern is a provisioned companion entitlement, not a physical
  bonded-vessel item. Repeated Egg/Soul Bond requests return the same grant.
- Miniwyvern death and revival preserve the entitlement. Dormant revival does
  not create a second projection before activation.
- Paid Dead or Lost revival restores the same profile and charges the entire
  configured AND recipe once, or charges nothing. A proven terminal failure
  produces one exact refund claim.
- Missing companion-inventory capability does not disable the Miniwyvern;
  profile-scoped virtual companion inventory remains intentionally deferred.

## Capability discipline

HyDragon must require each capability used by an action and treat an
unadvertised or currently unavailable surface as unavailable. API version
numbers and old development notes do not authorize private persistence access.
Fail closed before taking an Egg, Draconic Stone, revival cost, or other player
resource.

Missing paid revival must not disable safe roster reads or dismissal. Missing
timed summoning must deny new projection but must not erase roster membership.
No Tamework SQLite type or internal persistence service may cross the HyDragon
boundary.
