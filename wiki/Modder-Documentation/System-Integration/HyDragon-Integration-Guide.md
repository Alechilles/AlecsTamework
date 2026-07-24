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
- Capture-policy configs and handlers may supply generic probabilistic capture
  rules without adding species-specific logic to Tamework.
- Existing command links, progression, config reads, events, and interaction
  extensions remain available through their advertised capabilities.

HyDragon must not write Tamework SQLite rows or treat a live entity UUID as the
companion's durable identity.

The retained capture contract uses normal spawner behavior: a successful
capture creates the configured filled item, while a failed probability roll
leaves the target and source item unchanged apart from configured failure
feedback and cooldown.

## Companion lifecycle

Tamework's established gameplay paths remain simple:

- the owner cap counts loaded owned NPCs;
- SimpleClaims directly controls configured breeding limits and native
  tamed-NPC damage;
- coops capture and release live NPCs;
- filled spawners release their stored NPCs; and
- linked death and Lost restoration are free.

The current runtime does not provide HyDragon-specific command-family rosters,
timed summoning, population groups, companion provisioning, captured-item coop
intake, or paid revival. HyDragon must own any feature outside the advertised
generic API without treating Tamework internals as an extension surface.

## Capability discipline

HyDragon must require each capability it uses and treat an unadvertised
surface as unavailable. API version numbers and old development notes do not
authorize private persistence access.

The fixture-free `/tw api test run hydragon-integrations` suite checks only the
generic prerequisites retained for this boundary: capture-policy readiness,
capture config resolution, and transactional profile data.
