# Mounted Glide Controller

Tamework's mounted glide controller is an opt-in beta mount mode for flying mounts. It is separate from the older `TameworkRide`/`TameworkFly` path.

## Behavior
- The mount uses normal native mount movement while grounded.
- Pressing jump while grounded launches the mount into glide flight. Mounting in mid-air also starts in glide flight.
- While flight is active, the mount naturally glides forward and slowly loses altitude.
- Mouse pitch has a strong effect on the glide path. Looking down gains stored speed and sinks faster. Looking up spends stored speed for lift and can stall if speed is too low.
- Pressing jump queues one flap request. Mounted jump packets may remain latched after a press, so repeat flaps currently require a fresh release/press instead of hold-to-repeat.
- Holding sprint while a flap fires converts that flap into a forward boost. Normal forward/run input does not convert flaps.
- Holding crouch applies an airbrake that drains speed and increases sink.
- Landing after the queued flap is spent returns the mount to native grounded movement.
- Q/drop, left-click, and right-click are not consumed for flight controls.

## Architecture
Mounted glide uses Hytale's native NPC mount flow for attachment. `TameworkMountedGlide` applies `NPCMountComponent` to the NPC and lets the base mount attachment system keep the rider seated.

Flight movement is driven on the rider, not by an NPC body motion or motion controller. `MountedGlidePlayerVelocitySystem` reads the native-mounted rider's movement, jump, sprint, crouch, look, and grounded state. It leaves native movement alone while grounded, then applies rider `Velocity` instructions after jump launch or mid-air mounting.

`MountGlideMovementConfig` still applies to the rider while mounted. By default, mounted glide uses the normal `Mount` movement config so grounded walking remains available. The bundled `Tamework_Mounted_Glide_Rider` movement config is still available for experiments that need to suppress vanilla locomotion, but it should not be used for mounts that need grounded walking.

On dismount, Tamework removes native mount state, resets the rider movement settings, and requests the NPC's original native role before clearing glide markers. This is required so the NPC can be mounted again.

## Required Role Wiring
The NPC role must still pass the normal mount interaction requirements, including `IsMountable`.

Add or override these role parameters:
```json
"IsMountable": { "Value": true },
"MountMode": { "Value": "TameworkMountedGlide" }
```

Optionally set `MountGlideMovementConfig` when a role needs a non-default rider movement profile. Omit it to use `Mount`. Set it to `none`, `off`, or `disabled` only when testing without a rider movement override.

Older templates may still include `MountGlideState`, `MountGlideController`, a `TameworkMountedGlide` motion controller entry, or a ridden-state body motion for compatibility with earlier experimental builds. Current mounted glide attachment and flight do not require authors to add those entries for active behavior.

## Glide Config Assets
Config assets live under:

`Server/Tamework/Mounts/Glide/*.json`

Configs resolve by `RoleIds` and `Priority`. Higher priority wins. If no config matches, the controller uses conservative built-in defaults.

Example:
```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": ["Mob_Tamework_Example"],
  "Input": {
    "HeldJumpAutoFlap": true,
    "SprintFlapMode": "FORWARD_BOOST",
    "CrouchMode": "AIRBRAKE"
  },
  "Glide": {
    "BaseSpeed": 10.0,
    "MinSpeed": 4.0,
    "MaxSpeed": 28.0,
    "PassiveSinkRate": 0.8,
    "PitchDownAcceleration": 8.0,
    "PitchDownSink": 3.0,
    "PitchUpLiftConversion": 7.0,
    "PitchUpSpeedDrain": 9.0,
    "StallThreshold": 5.0,
    "StallSink": 4.5
  },
  "Flap": {
    "CooldownSeconds": 0.85,
    "UpwardBoostStrength": 6.0,
    "ForwardBoostStrength": 7.0,
    "BoostDurationSeconds": 0.25
  },
  "Airbrake": {
    "SpeedDecay": 6.0,
    "SinkMultiplier": 1.75
  }
}
```

The bundled `TwMountedGlideExample` profile shows the full field set.

## Input Notes
The current implementation reads mounted movement, jump, sprint, crouch, and look rotation. Jump is treated as a press edge because native mounted jump packets may keep reporting `true` after a single press while airborne. Forward/run movement is only movement intent; only the native sprint flag selects a forward flap. It does not consume Q/drop, left-click, or right-click for flight controls.

Tamework does not currently install an F/use packet filter for mounted glide. If post-pivot manual testing shows the use key re-enters the interaction prompt while mounted, that should be handled as a separate runtime fix.
