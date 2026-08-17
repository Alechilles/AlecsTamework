---
name: tamework-avatar-flight
description: Use when changing Tamework avatar flight activation, launch charge, packet input, movement, vigour, combat abilities, transformed player models, mounts, fake riders, source NPC parking or recovery, equipment visibility, animations, VFX, audio, HUD, dismount, disconnect, world transfer, or flight cleanup.
---

# Tamework Avatar Flight

Treat avatar flight as a coordinated session. Input, player movement, source
NPC state, visuals, and cleanup must agree on the same lifecycle.

## Map the Session

1. Read `references/avatar-flight-lifecycle.md`.
2. Read `docs/Avatar-Flight.md` and the relevant config under
   `Server/Tamework/AvatarFlight`.
3. Trace activation, packet capture, input component, movement controller,
   session/source components, transformed model, rider visual, equipment,
   animation, VFX/audio, HUD, and teardown.
4. Identify the current owner and clock for every timer. Do not add a timer to
   a tick system when a component or focused service already owns the state.
5. Use `hytale-workshop-mcp` before designing behavior that depends on
   base-game packet order, mounting, player transfer, model, equipment, or
   entity-lifetime semantics not proven by current repo evidence.

## Preserve Identity and World Ownership

- Entity refs, components, `Player`, HUD objects, mounts, and rider entities are
  not portable across worlds or threads.
- Carry stable UUIDs, session/runtime epochs, config IDs, and immutable handoff
  data. Resolve all live refs in the destination world.
- Validate player, source NPC, source world, destination world, session epoch,
  and role/config before reconstruction.
- A world transfer is not an ordinary pause. Define whether it performs full
  teardown or an explicit handoff followed by destination reconstruction.
- Never leave the source NPC hidden, frozen, intangible, invulnerable, or
  parked without a bounded recovery owner.

## Keep Surfaces Synchronized

- Packet press/release ordering must match `AvatarFlightInputComponent` and the
  movement tick that consumes it.
- Model, rider visual, equipment visibility, hotbar/inventory guards,
  animations, VFX, audio loops, and HUD must enter and leave together.
- Every exit path must be idempotent: normal dismount, denied activation,
  target/source loss, death, disconnect, world transfer, stale session, plugin
  shutdown, and crash recovery.
- Use focused services and `CommandBuffer`; keep systems as orchestration.

## Route Related Work

- Use `$tamework-config-authoring` for `TwAvatarFlightConfig` schema,
  inheritance, defaults, or editor behavior.
- Use `$tamework-runtime-safety` for ECS, threading, tick, cadence, or async
  changes.
- Use `$particle-emotion-pipeline` only for reusable particle authoring; avatar
  flight still owns when its VFX starts and stops.
- Use `$tamework-companion-progression` for vigour or progression semantics.

## Verify the Lifecycle

1. Test observable activation or transition behavior and the relevant cleanup.
   Do not test only component fields, registration calls, or asset presence.
2. For transfer or recovery work, test valid handoff, stale epoch, missing
   source, wrong world, duplicate cleanup, and destination reconstruction where
   each is a real failure mode.
3. Run runtime guard checks from `docs/agents/guardrails.md` and
   `bash ../gradlew -p .. :alecstamework:test`.
4. Verify player-visible model, equipment, rider, HUD, audio, and source NPC
   state in a live server when static tests cannot prove the result.
5. Report session identity, input ordering, world ownership, reconstruction,
   all cleanup surfaces, Workshop evidence, and remaining live gaps.
