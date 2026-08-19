# Actions, Sensors, and Components

This file maps Tamework's currently registered NPC builders, item interactions, runtime components, and `/tw` commands.

## Shared NPC Instruction Components

Use these components from downstream role assets with `Reference` and override
species tuning through `Modify`. Do not copy their instruction bodies into each
mod; consuming the shared IDs lets future Tamework fixes apply automatically.

### `Component_Tamework_Instruction_Follow_Large`

Ground follow behavior for large NPCs. It seeks its owner at close range,
maintains a configurable separation, and teleports after a configurable maximum
distance. Parameters:

- `MasterTargetSlot`
- `FollowTeleportThresholdRange`
- `FollowSeekSlowDownDistance`
- `FollowSeekStopDistance`
- `FollowMaintainDistanceRange`
- `FollowRelativeSpeed`

```json
{
  "Reference": "Component_Tamework_Instruction_Follow_Large",
  "Modify": {
    "MasterTargetSlot": "MasterTarget",
    "FollowTeleportThresholdRange": 60,
    "FollowSeekSlowDownDistance": 32,
    "FollowSeekStopDistance": 16,
    "FollowMaintainDistanceRange": [16, 24],
    "FollowRelativeSpeed": 1
  }
}
```

### `Component_Tamework_Instruction_Follow_Flying`

Autonomous flying follow behavior that takes off from `Walk`, maintains a
target-relative altitude, wanders around its owner with
`TameworkFlyingOrbit`, teleports after extreme separation, and hovers safely
when the owner target is temporarily unavailable. Parameters:

- `MasterTargetSlot`
- `FollowDesiredAltitudeRange`
- `FollowTeleportThresholdRange`
- `FollowOrbitRadiusRange`
- `FollowOrbitRetargetTimeRange`
- `FollowOrbitStopDistance`
- `FollowOrbitRelativeSpeed`
- `FollowHoverRadius`
- `FollowHoverRelativeSpeed`

```json
{
  "Reference": "Component_Tamework_Instruction_Follow_Flying",
  "Modify": {
    "MasterTargetSlot": "MasterTarget",
    "FollowDesiredAltitudeRange": [4, 8],
    "FollowTeleportThresholdRange": 60,
    "FollowOrbitRadiusRange": [16, 24],
    "FollowOrbitRetargetTimeRange": [3, 6],
    "FollowOrbitStopDistance": 3,
    "FollowOrbitRelativeSpeed": 0.65,
    "FollowHoverRadius": 1.75,
    "FollowHoverRelativeSpeed": 0.12
  }
}
```

### `Component_Tamework_Instruction_Hold_Flying`

Flying Hold behavior for companions using Tamework's managed landing
controller. It releases combat, waits for the landing controller to complete,
applies a grounded animation once, and remains stationary after touchdown.
Parameters:

- `HoldGroundAnimation`
- `HoldLandingSearchRange`
- `HoldLandingSearchAngle`
- `HoldLandingSlowDownDistance`
- `HoldLandingStopDistance`
- `HoldLandingGoalLenience`

```json
{
  "Reference": "Component_Tamework_Instruction_Hold_Flying",
  "Modify": {
    "HoldGroundAnimation": "Idle"
  }
}
```

### `Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying`

Aerial counterpart to `Component_Tamework_Instruction_SeekFood_PlayerFollow`.
It pursues a non-hostile player holding an attractive item, lands safely near
the target, approaches on foot, and returns to the imported `Idle` parent state
when the item is lost. Parameters:

- `_ImportStates`
- `AttractiveItemSet`
- `FollowTargetSlot`
- `LandingPositionSlot`
- `FlightSeekStopDistance`
- `GroundApproachDistanceRange`

```json
{
  "Reference": "Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying",
  "Modify": {
    "AttractiveItemSet": ["Food_Fish_Raw"],
    "FollowTargetSlot": "LockedTarget",
    "LandingPositionSlot": "MyMod_Aerial_Favorite_Landing",
    "FlightSeekStopDistance": 5,
    "GroundApproachDistanceRange": [1.5, 2]
  }
}
```

### `Component_Tamework_Instruction_Airborne_Mode_Transition`

Parameterized autonomous transition between native `Walk` and `Fly` motion
controllers. It consumes a downstream hook, toggles an airborne flag, respects
a grounded-activity gate, and performs takeoff or safe ray-based landing.
Parameters:

- `ToggleAirborneModeHookId`
- `AirborneModeFlagName`
- `GroundedActivityFlagName`
- `LandingRayName`
- `LandingBlocks`
- `LandingSearchRange`
- `LandingSearchAngle`
- `LandingSlowDownDistance`
- `LandingStopDistance`
- `LandingHeightDifference`
- `LandingGoalLenience`
- `LandingDesiredAltitudeWeight`

Consumers without a grounded activity should use a private flag name that they
never set; the component's `Set: false` gate then remains open.

```json
{
  "Reference": "Component_Tamework_Instruction_Airborne_Mode_Transition",
  "Modify": {
    "ToggleAirborneModeHookId": "MyMod.Command.ToggleAirborneMode",
    "AirborneModeFlagName": "AirborneMode",
    "GroundedActivityFlagName": "MyMod_UnusedGroundedActivity",
    "LandingRayName": "MyMod_AirborneMode_LandingRay"
  }
}
```

## NPC Action Builder IDs
- `TameworkInteract`: Runs the optimized interaction pipeline (`TwInteractionConfig`).
- `TameworkInteractPrompt`: Updates prompt text from the first currently matching interaction entry.
- `TameworkCaptureOwner`: Captures an owned NPC into a spawner item.
- `TameworkCaptureStranger`: Captures another player's owned NPC when policy allows.
- `TameworkCaptureWild`: Captures untamed NPCs.
- `TameworkConfirmLanding`: Switches a flying NPC to its `Walk` controller after the active
  flight controller reports physical ground contact. Use it with an `OnGround` sensor when a
  large or pitched collision box can touch terrain before the base `Land` motion reaches its
  positional goal.
- `TameworkDenyCaptureUntamed`: Blocks capture when tame is required.
- `TameworkDenyInteract`: Blocks player interaction (typically non-owner gating).
- `TameworkSetOwner`: Assigns owner from the interacting player. Vanilla action lists that also
  tame or consume an item should configure `TameOnApplied`, `ConsumeHeldItemOnApplied`,
  `StateOnApplied`, `ParticleSystemOnApplied`, and `SoundEventParamOnApplied` on this action. Those
  effects then run only from the admitted owner-mutation continuation; do not add eager sibling
  inventory/tame success actions to the same list.
- `TameworkSetTamed`: Sets/clears tamed state.
- `TameworkNeedsResourceConsume`: Consumes configured needs resource targets (food/water seek flows).
- `TameworkNeedsResourceRejectTarget`: Temporarily suppresses a failed needs seek target so later scans can choose another reachable source.
- `TameworkNeedsResourceReleaseTarget`: Releases a successful needs seek target reservation without marking it as failed.
- `TameworkForgetHostileTarget`: Removes the current sensor target from hostile target memory so it cannot be reacquired.
- `TameworkRejectPositionTarget`: Temporarily suppresses a failed generic position target for the current NPC.
- `TameworkHarvestDrop`: Drops harvest outputs with trait-aware bonus support.
- `TameworkDebugMessage`: Emits debug text from instruction flows.

## NPC Sensor Builder IDs
- `TameworkIsOwner`
- `TameworkHasOwner`
- `TameworkIsTamed`
- `TameworkLifeStage`
- `TameworkAlarm` (mirrors base `Alarm` sensor syntax for Tamework alarms: `Name`, `State`, optional `Clear`)
- `TameworkHook`
- `TameworkEffectActive` (checks active `EntityEffect` with optional `MinRemainingSeconds`)
- `TameworkHasTalent` (checks this NPC's purchased talent ID, for example `{ "Type": "TameworkHasTalent", "TalentId": "DraconicProjectile" }`)
- `TameworkNeedBelow`
- `TameworkNeedsResourceFastMode`: Matches while `/tw settings` has active needs fast-consume behavior.
- `TameworkNeedsResourceTarget`
- `TameworkReachableBlockTarget` (finds a matching block set or exact block type, exposes a projected and path-preflighted approach position)

`TameworkNeedsResourceTarget` reads short-lived local targets and shared area
results before it requests new work. A cold lookup can return `false` for one
or more world ticks while the bounded resource-search worker processes it.
Later sensor checks use the shared result immediately. Equivalent requests from
nearby NPCs share one cold search, while rejection and reservation filters stay
specific to each NPC.

`TameworkHook` context fields:
- `HookId`
- `HookPlayerId`
- `HookPlayerName`
- `HookHeldItemId`
- `HookTimestampMs`
- `HookHasTargetPosition`
- `HookTargetX`
- `HookTargetY`
- `HookTargetZ`

## NPC Entity Filter Builder IDs
- `TameworkAttitudeFromTargetSlot`
- `TameworkAttackedTargetSlotRecently`

## Runtime ECS Components
- `TameworkOwnerComponent`
- `TameworkTamedComponent`
- `TameworkHookComponent`
- `TameworkNpcNameComponent`
- `TameworkMountedNameplateComponent`
- `TameworkCommandLinksComponent`
- `TameworkHappinessComponent`
- `TameworkNeedsComponent`
- `TameworkBreedingComponent`
- `TameworkTraitsComponent`
- `TameworkAttachmentsComponent`
- `TameworkLifeStageComponent`
- `TameworkAvatarFlightMountSession` (player-side NPC/config link, phase, origin, last safe ground, and dismount hold state)
- `TameworkAvatarFlightSource` (source-NPC reverse link and role/transform/visibility recovery snapshot)

## Item Interactions
- `TameworkSpawn`
- `TameworkNameNpc`
- `TameworkCommand`

## `/tw` Commands
- `/tw getowner`
- `/tw setowner`
- `/tw gettamed`
- `/tw settamed`
- `/tw getalarm [AlarmName] [NpcUuid]`
- `/tw reloadconfig`
- `/tw gethappiness`
- `/tw sethappiness <value>`
- `/tw debug get needs [--entity=<uuid>|--ray|--cone|--coneAll|--sphere] [--world=<world>] [--angle=<degrees>] [--range=<blocks>] [--roles=<role,...>] [--nearest]`
- `/tw debug set needs <hunger> <thirst> [NPC selectors]`
- `/tw debug set hunger <value> [NPC selectors]`
- `/tw debug set thirst <value> [NPC selectors]`
- `/tw debug set breeding ready [--mode=true|false|toggle] [NPC selectors]`
- `/tw spawntamed <role> [--count=<quantity>] [--radius=<blocks>] [--attachment=<slot:value>]`
- `/tw gettraits`
- `/tw settraits <TraitId> <Value> [TraitId Value ...]`
- `/tw addtrait <TraitId> <Value>`
- `/tw getlifestage`
- `/tw findnpc <uuid> [mark:on|off]`
- `/tw getflockdebug`
- `/tw debughook [on|off]`
- `/tw debugprompt [on|off]`
- `/tw debugspawner [on|off]`
- `/tw debugspawnerlocation [on|off]`
- `/tw debugdespawn [on|off] [RoleName|all|clear]`
- `/tw debugplayermodel unsafe [ModelId] [scale] | reset | status`
- `/tw debugplayerinput [on|off|status]`
- `/tw debuglag [on|off]`
- `/tw showspawnbeacons [radius|off]`
- `/tw showspawnmarkers [radius|off]`
- `/tw deletespawnmarker [range]`

`NPC selectors` use Hytale's standard NPC debug selection: `--world`, `--entity`, `--angle`,
`--range`, `--roles`, `--nearest`, `--ray`, `--cone`, `--coneAll`, and `--sphere`.

## Notes
- Components persist across reloads.
- `TriggerNpcHook` + `TameworkHook` is the primary bridge from optimized interactions into instruction branches.
- `TameworkAlarm` is the instruction-side reset bridge for durable Tamework alarm state.
- `TameworkEffectActive` is useful for gating behavior while status effects (for example tranquilizer) are active.
- `/tw reloadconfig` only reloads item-feature assets (`TwSpawnerConfig`, `TwNameItemConfig`, `TwCommandItemConfig`).
