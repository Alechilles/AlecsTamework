# Mounted Glide Controller Design

## Goal

Create a clean-slate mounted glide controller for Tamework flying mounts. The controller should feel closer to a dragonriding-style glide loop than the previous beta mounted flight controller: mounts naturally glide forward, slowly lose altitude, gain speed when diving, can trade stored speed for altitude when pitching up, and use cooldown-gated wing flaps for discrete boosts.

This design intentionally does not build on the previous `TameworkFly` mounted flight state model. Existing mounted ride code can stay in the repo as legacy behavior, but the new controller should have its own component, input capture, motion controller, config family, cleanup path, and docs.

## Base-Game Evidence

Hytale Workshop MCP was queried against Hytale `0.5.6`.

- Mounted players still have a `PlayerInput` queue. `com.hypixel.hytale.builtin.mounts.MountSystems.HandleMountInput#tick` handles `PlayerInput.WishMovement`, relative/absolute movement, body/head updates, and movement states before clearing the queue.
- Jump, crouch, sprint, running, flying, and on-ground state are available through `MovementStates`, including mounted paths that use `PlayerInput.SetMovementStates` and `PlayerInput.SetRiderMovementStates`.
- Mouse look and mouse buttons arrive through `com.hypixel.hytale.protocol.packets.player.MouseInteraction`. `com.hypixel.hytale.server.core.modules.interaction.InteractionModule#doMouseInteraction` dispatches button events for left/right buttons, but those inputs already overlap attack/use/interact behavior.
- The Q/drop key is represented as inventory drop packets/events such as `DropItemStack` and `DropItemEvent.PlayerRequest`, not as movement input. It is technically interceptable, but it is not a good default flight control.
- NPC motion controllers expose velocity paths such as `MotionControllerBase#setVelocity`, `MotionControllerBase#addVelocity`, and `MotionControllerFly#takeOff`. The first implementation should prefer a dedicated motion controller over direct player velocity mutation.

## Player-Facing Behavior

The mount always tries to glide forward while ridden and airborne. With no rider input, it keeps moving but gradually sinks.

Mouse look pitch heavily influences the glide:

- Pitching down increases forward speed and sink rate.
- Flying near level maintains a moderate glide with slight altitude loss.
- Pitching up trades stored forward speed for temporary lift.
- Pitching up too steeply or too long without enough speed causes a stall-like stronger sink.

Flapping is cooldown-gated, not continuous thrust:

- Holding jump requests flaps.
- If the flap cooldown is ready, a requested flap fires immediately.
- If jump remains held, the next flap fires automatically when the cooldown expires.
- Releasing jump stops future automatic flap requests.
- A normal flap is upward-biased.
- Sprinting during a flap changes that flap into a forward-biased boost.

Crouch acts as an airbrake:

- Forward speed decays faster.
- Descent becomes more controlled or stronger depending on config.
- Turning/control may increase while braking.

WASD should provide directional intent and lateral correction, but should not create freeform noclip-style movement. Left/right strafe should matter, but the main movement loop should remain glide speed plus pitch conversion.

## Architecture

Add a new mounted glide stack under Tamework. Tentative names are listed for clarity; implementation can adjust names if local conventions suggest better ones.

- `TameworkMountedGlideComponent`: mount-side runtime component for rider linkage, input snapshot, glide physics state, cooldowns, restore data, and stale-session safety.
- `TameworkMountedGlideRiderComponent`: lightweight player-side marker that identifies the active glide mount.
- `MountedGlideInputCaptureSystem`: reads mounted `PlayerInput` before vanilla mount handling consumes or applies it. It writes normalized input state into `TameworkMountedGlideComponent`.
- `MountedGlidePacketHandler`: active-session packet supplement for fresher look/wish snapshots. It should not become the source of unrelated input hacks.
- `BodyMotionTameworkMountedGlide`: suppresses normal AI body motion while mounted and produces steering from the glide snapshot.
- `MotionControllerTameworkMountedGlide`: owns glide physics, speed storage, pitch conversion, flap impulses, airbrake behavior, stall behavior, movement-state updates, animation hints, and collision recovery.
- `MountedGlideCleanupSystem`: restores previous NPC state/controller and removes rider/mount markers on dismount, invalid refs, death, world transfer, or stale sessions.
- `TwMountedGlideConfig`: role-scoped config family for all tuning values.

The new path should not read or write `TameworkRideMountComponent` and should not depend on `MotionControllerTameworkFly`. Shared helper code is acceptable only when it is generic, small, and does not preserve the old controller's state assumptions.

## Config Family

Add `TwMountedGlideConfig` under `Server/Tamework/Mounts/Glide/*.json`.

Resolution should be role-scoped and priority-based, matching other Tamework role-scoped config families. Parent fallback must follow the asset inheritance contract:

- Omitted top-level sections inherit from parent.
- Explicit object sections inherit missing nested keys.
- Explicit arrays/maps replace parent values.
- Codec docs must describe inheritance behavior for each section.

Recommended sections:

- `Eligibility`: enabled flag, role ids, and optional required role params.
- `Input`: held-jump auto-flap, sprint flap mode, crouch mode.
- `Glide`: base speed, min/max speed, passive sink, pitch-down acceleration, pitch-up lift conversion, pitch-up speed drain, stall threshold, stall sink.
- `Flap`: cooldown seconds, upward boost strength, forward boost strength, boost duration, input grace.
- `Airbrake`: speed decay, sink multiplier, turn/control multiplier.
- `Safety`: stale input timeout, max pitch clamp, max vertical speed, collision recovery ticks.
- `Presentation`: optional movement animation names and debug labels.

The default profile should be conservative: clear glide, clear flaps, no extreme speed, and enough pitch effect to feel intentional without causing collision instability.

## Data Flow

1. A mount interaction starts a glide session by applying the new mount and rider components, storing previous NPC state/controller data, and selecting the new body motion/controller IDs.
2. Input capture reads the rider's mounted queue before vanilla mounted input applies target position changes. It captures movement intent, jump held state, sprint, crouch, body/head look, and last input time.
3. Packet handling supplements the snapshot for active glide sessions when client movement or mouse motion packets contain fresher look/wish data.
4. The mount component converts raw input into durable control state: jump requested, flap-ready timing, sprint modifier, airbrake state, forward/strafe intent, yaw, and pitch.
5. Body motion turns that state into steering and keeps normal NPC AI from fighting the rider.
6. The glide motion controller updates stored speed and vertical movement from pitch, flap impulses, airbrake, and stall rules. It writes the final translation through the NPC motion controller path.
7. Cleanup removes components and restores the NPC on dismount, invalid refs, death, transfer, or session mismatch.

## Physics Model

The controller tracks stored glide speed independently from immediate input. This avoids a simple "look direction equals velocity" feel.

Each tick while ridden:

1. Resolve pitch from rider look and clamp it through config.
2. Apply passive sink.
3. If pitch is below level, add dive acceleration and additional sink.
4. If pitch is above level, convert stored forward speed into lift and drain speed.
5. If stored speed falls below the stall threshold while pitch-up demand remains high, apply stall sink and reduce lift.
6. If jump is held and the flap cooldown is ready, apply a flap impulse:
   - without sprint: upward-biased boost
   - with sprint: forward-biased boost
7. If crouch is held, apply airbrake speed decay and configured descent/control changes.
8. Apply acceleration/deceleration limits, max speed, max vertical speed, and collision recovery.

This should create a repeatable loop: dive to build speed, pull up to gain altitude, flap to recover altitude or momentum, and airbrake to slow or descend.

## Error Handling And Safety

- If the rider or mount ref is missing or invalid, cleanup should remove both markers and restore the mount if possible.
- If the configured profile is missing, disabled, or invalid for a role, the mount interaction should fail gracefully or fall back to a safe default profile with a warning that includes role and asset IDs.
- Runtime systems must use `CommandBuffer` for ECS writes in system callbacks.
- Player component access in systems must resolve through the current world/store and must not use `PlayerRef.getComponent(Player)` in tick paths.
- Mouse button and Q/drop input should not be consumed in v1.
- Collision recovery should stop repeated horizontal shove into terrain and preserve a controllable escape path.

## Testing

Add focused Java tests for logic that can be tested without a live client:

- Flap cadence: held jump fires one flap per cooldown and releasing jump stops future queued flaps.
- Sprint-flap selection: sprint held at flap time selects the forward-biased impulse.
- Pitch conversion: down pitch increases speed/sink; up pitch drains speed and can produce lift; low speed plus pitch-up enters stall behavior.
- Airbrake: crouch decays speed and changes sink/control according to config.
- Config inheritance: object sections inherit missing nested keys, explicit arrays/maps replace parent values, and codec docs cover inheritance.
- Safety guards: keep `EcsWriteSafetyGuardTest` and `AsyncThreadSafetyGuardTest` passing if runtime systems are touched.

Manual validation should use `/tw debugride` or a new scoped debug command to log pitch, stored speed, vertical velocity, flap cooldown, active boost, airbrake, and stall state at a throttled cadence.

## Documentation

When implemented, update:

- `CHANGELOG.md` with player-facing mounted glide behavior.
- `docs/Interactions.md` or a dedicated mounted-glide doc with setup examples.
- Wiki config reference for `TwMountedGlideConfig`.
- Example role/template assets showing a basic glide mount profile.

## Scope And Naming Rules

- Final class and builder IDs should follow nearby Tamework registration conventions and keep `MountedGlide` / `TameworkMountedGlide` naming unless a direct conflict appears.
- The first version must not implement stamina, mouse-button controls, Q/drop controls, or client-authoritative movement.
- Legacy mounted flight remains available until the new controller is proven stable enough to replace it.
