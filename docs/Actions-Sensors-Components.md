# Actions, Sensors, and Components

This file maps Tamework's currently registered NPC builders, item interactions, runtime components, and `/tw` commands.

## NPC Action Builder IDs
- `TameworkInteract`: Runs the optimized interaction pipeline (`TwInteractionConfig`).
- `TameworkInteractPrompt`: Updates prompt text from the first currently matching interaction entry.
- `TameworkCaptureOwner`: Captures an owned NPC into a spawner item.
- `TameworkCaptureStranger`: Captures another player's owned NPC when policy allows.
- `TameworkCaptureWild`: Captures untamed NPCs.
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
- `TameworkNeedBelow`
- `TameworkNeedsResourceFastMode`: Matches while `/tw settings` has active needs fast-consume behavior.
- `TameworkNeedsResourceTarget`
- `TameworkReachableBlockTarget` (finds a matching block set or exact block type, exposes a projected and path-preflighted approach position)

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
