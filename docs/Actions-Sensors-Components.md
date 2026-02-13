# Actions, Sensors, and Components

This file maps the custom behavior surface area currently implemented in Alec's Tamework.

## Actions (NPC instructions)
- `TameworkInteract`: Runs the optimized interaction pipeline using `TwInteractionConfig` assets. Accepts optional overrides for `ConfigId`, `LovedItems`, `IsMountable`, `IsHarvestable`, and `HarvestInteractionContext` (otherwise role params are used).
- `TameworkCaptureOwner`: Captures an owned NPC into a spawner item (owner-only capture path).
- `TameworkCaptureStranger`: Captures an owned NPC as a non-owner (used when capture rules allow it).
- `TameworkCaptureWild`: Captures an untamed NPC into a spawner item.
- `TameworkDenyCaptureUntamed`: Blocks capture attempts when the NPC is not tamed (used by some role flows).
- `TameworkDenyInteract`: Blocks interaction when an owner exists and the player is not the owner (sends a denial message).
- `TameworkSetOwner`: Sets the owner component from the interacting player.
- `TameworkSetTamed`: Sets the tamed component to true or false.

## Sensors (NPC instructions)
- `TameworkIsOwner`: True when the current interaction player matches the stored owner.
- `TameworkHasOwner`: True when the NPC has an owner component.
- `TameworkIsTamed`: True when the NPC has the tamed component set.
- `TameworkHook`: True when the NPC has a matching hook signal. Emits extra info params:
  `HookId`, `HookPlayerId`, `HookPlayerName`, `HookHeldItemId`, `HookTimestampMs`.

## Components
- `TameworkOwnerComponent`: Stores owner UUID and name.
- `TameworkTamedComponent`: Stores tamed boolean.
- `TameworkHookComponent`: Stores the latest hook signal for instruction bridges.

## Item interactions
- `TameworkSpawn`: Custom item interaction used by spawner items to capture or spawn NPCs.

## Commands
- `/tw getowner` prints the owner name + UUID (when available).
- `/tw gettamed` prints the current tamed status for the targeted NPC.
- `/tw getalarm [AlarmName] [NpcUuid]` prints alarm status (unset/active/passed) and remaining time when set. Uses the NPC in view if no UUID is provided. Defaults to `Harvest_Ready` when no alarm name is supplied.
- `/tw setowner` assigns the targeted NPC to the executing player.
- `/tw settamed` toggles the tamed flag on the targeted NPC.
- `/tw reloadconfig` reloads spawner item configs from disk (TwSpawnerConfig assets).

## Notes
- Components persist across reloads.
- `TameworkHook` can be paired with the `TriggerNpcHook` effect in `TwInteractionConfig` to bridge interaction logic into custom NPC instructions.
- `TameworkInteract` logs a warning when no interaction matches, which is useful for debugging requirements.
