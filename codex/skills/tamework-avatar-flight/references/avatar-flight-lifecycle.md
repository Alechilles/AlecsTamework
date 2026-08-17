# Avatar Flight Lifecycle

| Phase | State that must agree |
| --- | --- |
| Preflight | Player, source NPC, role/config, world, capability, and mount policy |
| Activation | Session epoch, source snapshot/parking, model, equipment, rider visual |
| Input | Packet press/release, queued action, timer owner, and tick consumption |
| Movement | Flight mode, vigour, velocity, collision, launch, flap, and boost |
| Presentation | Animation, trail/VFX, audio loops, HUD, hotbar, and equipment |
| Exit | Reason, input cancellation, movement disable, model/equipment restore |
| Recovery | Source NPC restore, stale owner cleanup, rider removal, registry cleanup |
| Transfer | Source teardown or explicit handoff, destination revalidation, rebuild |

## Identity Rules

- UUID text can cross a boundary; a resolved entity ref cannot.
- A session epoch prevents stale cleanup from destroying a newer session.
- A source snapshot describes restoration data; it is not proof that the
  source entity still exists in the expected world.
- A visual rider is not the authority for the player or source NPC.
- Destination reconstruction must be safe when the source is missing or the
  transfer repeats.

## Useful Starting Points

Verify all names in current source:

- `AvatarFlightActivator` and `AvatarFlightController`
- `AvatarFlightInputComponent`, `AvatarFlightPacketInputCapture`, and
  `AvatarFlightMovementSystem`
- `AvatarFlightMountSessionComponent`, `AvatarFlightMountSessionSystem`, and
  `AvatarFlightMountLifecycleService`
- model, rider visual, equipment, animation, VFX, audio, and HUD services
- source visibility, recovery, stale-owner, disconnect, and restoration policy
  classes
