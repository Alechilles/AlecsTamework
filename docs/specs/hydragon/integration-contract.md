# HyDragon Integration Contract

Status: supported minimal 3.0.0 boundary

## Supported authority

Tamework owns canonical companion identity, owner, lifecycle, and location.
HyDragon stores the stable profile ID and uses namespaced `ProfileDataApi`
values for HyDragon-specific state. It must not shadow Tamework lifecycle in
its namespace.

When `PROFILE_DATA_TRANSACTIONS` is advertised, HyDragon may use versioned
reads, revision-fenced compare-and-set, stable idempotency keys, and operation
lookup after restart. Those operations mutate only namespaced extension data;
they are not a generic lifecycle mutation surface.

HyDragon may also consume advertised capture-policy, progression, command-link,
config-read, event, interaction-extension, and trait-effect surfaces.
Command links identify existing released tool relationships; they are not a
durable command roster.

## Gameplay behavior

- Owner capacity is the simple Tamework cap over currently loaded owned NPCs.
  It is not a durable population or population-group authority.
- Breeding claim limits and tamed-NPC damage are direct SimpleClaims checks.
- Coops capture and release live NPCs.
- Filled spawners release their stored NPC.
- Death and lost restoration are free.

## Capability discipline

HyDragon must treat every unadvertised surface as unavailable, regardless of
the API version. It must not infer private operations or write Tamework's
persistence directly.

In particular, the supported boundary has no population-group, command-roster,
timed-summon, provisioning, paid-revival, captured-item-to-coop, or companion-
inventory authority. Reintroducing one requires a new public design and
capability; no July table, operation name, or proposal is a compatibility
contract.
