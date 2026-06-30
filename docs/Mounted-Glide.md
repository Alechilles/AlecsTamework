# Mounted Glide Controller

Tamework's mounted glide controller is an opt-in beta mount mode for flying mounts. It is separate from the older `TameworkRide`/`TameworkFly` path.

## Behavior
- The mount naturally glides forward while ridden and slowly loses altitude.
- Mouse pitch has a strong effect on the glide path. Looking down gains stored speed and sinks faster. Looking up spends stored speed for lift and can stall if speed is too low.
- Holding jump requests flaps repeatedly, but each flap is still limited by `Flap.CooldownSeconds`.
- Holding sprint while a flap fires converts that flap into a forward boost.
- Holding crouch applies an airbrake that drains speed and increases sink.

## Required Role Wiring
The NPC role must still pass the normal mount interaction requirements, including `IsMountable`.

Add or override these role parameters:
```json
"IsMountable": { "Value": true },
"MountMode": { "Value": "TameworkMountedGlide" },
"MountGlideState": { "Value": "Ridden" },
"MountGlideController": { "Value": "TameworkMountedGlide" }
```

Add a dormant motion controller entry:
```json
{
  "Type": "TameworkMountedGlide"
}
```

Add a ridden-state body motion:
```json
{
  "Sensor": {
    "Type": "State",
    "State": "Ridden"
  },
  "BodyMotion": {
    "Type": "TameworkMountedGlide"
  }
}
```

The bundled example templates include this dormant wiring. Set `MountMode` to `TameworkMountedGlide` and `IsMountable` to `true` on a role that uses those templates to opt in.

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
The current implementation reads mounted movement, jump, sprint, crouch, and look rotation. It does not consume Q/drop, left-click, or right-click for flight controls.
