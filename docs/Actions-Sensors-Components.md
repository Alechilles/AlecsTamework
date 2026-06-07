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
- `TameworkSetOwner`: Assigns owner from interacting player.
- `TameworkSetTamed`: Sets/clears tamed state.
- `TameworkNeedsResourceConsume`: Consumes configured needs resource targets (food/water seek flows).
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
- `TameworkNeedsResourceTarget`

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
- `/tw getneeds`
- `/tw setneeds <hunger> <thirst> [aoe <radius>]`
- `/tw sethunger <value>`
- `/tw setthirst <value>`
- `/tw setbreedingready [true|false|toggle]`
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
- `/tw debuglag [on|off]`
- `/tw showspawnmarkers [radius|off]`
- `/tw deletespawnmarker [range]`

## Notes
- Components persist across reloads.
- `TriggerNpcHook` + `TameworkHook` is the primary bridge from optimized interactions into instruction branches.
- `TameworkAlarm` is the instruction-side reset bridge for durable Tamework alarm state.
- `TameworkEffectActive` is useful for gating behavior while status effects (for example tranquilizer) are active.
- `/tw reloadconfig` only reloads item-feature assets (`TwSpawnerConfig`, `TwNameItemConfig`, `TwCommandItemConfig`).
