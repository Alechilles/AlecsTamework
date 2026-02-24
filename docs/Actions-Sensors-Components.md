# Actions, Sensors, and Components

This file maps the custom behavior surface area currently implemented in Alec's Tamework.

## Actions (NPC instructions)
- `TameworkInteract`: Runs the optimized interaction pipeline using `TwInteractionConfig` assets. Accepts optional overrides for `ConfigId`, `LovedItems`, `IsMountable`, `IsHarvestable`, and `HarvestInteractionContext` (otherwise role params are used; defaults are defined in `TwGlobalConfig`).
- `TameworkInteractPrompt`: Updates the NPC interaction prompt based on the first matching interaction entry (supports `PromptHint` + `ShowPrompt` per entry).
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
- `TameworkIsTamed`: True when the NPC has the tamed component set or its role id starts with `Tamed`.
- `TameworkHook`: True when the NPC has a matching hook signal. Emits extra info params:
  `HookId`, `HookPlayerId`, `HookPlayerName`, `HookHeldItemId`, `HookTimestampMs`,
  `HookHasTargetPosition`, `HookTargetX`, `HookTargetY`, `HookTargetZ`.

## Components
- `TameworkOwnerComponent`: Stores owner UUID and name.
- `TameworkTamedComponent`: Stores tamed boolean.
- `TameworkHookComponent`: Stores the latest hook signal for instruction bridges.
- `TameworkNpcNameComponent`: Stores custom NPC name metadata (name, owner id, timestamp). Names are re‑applied on load and preserved by spawner capture.
- `TameworkCommandLinksComponent`: Stores per-owner command tool links on NPCs.
- `TameworkHappinessComponent`: Stores shared per-NPC happiness progression state (config id, value, last update).
- `TameworkBreedingComponent`: Stores per-NPC breeding progression state (config id, mirrored happiness/readiness, cooldown, partner).
- `TameworkTraitsComponent`: Stores rolled trait values and deterministic trait seed per NPC (assigned during progression bootstrap when role trait config is enabled).

## Item interactions
- `TameworkSpawn`: Custom item interaction used by spawner items to capture or spawn NPCs.
- `TameworkNameNpc`: Custom item interaction used by naming items to name tamed NPCs via an input UI (with chat fallback if UI open fails).
- `TameworkCommand`: Custom item interaction used by command tools for linking, command selection, and command dispatch.

## Commands
- `/tw getowner` prints the owner name + UUID (when available).
- `/tw gethappiness` prints the current happiness value/source and breeding eligibility context for the targeted NPC.
- `/tw gettamed` prints the current tamed status for the targeted NPC (component or `Tamed*` role id).
- `/tw getalarm [AlarmName] [NpcUuid]` prints alarm status (unset/active/passed) and remaining time when set. Uses the NPC in view if no UUID is provided. Defaults to `TwGlobalConfig.HarvestAlarmName` (default `Harvest_Ready`) when no alarm name is supplied.
- `/tw setowner` assigns the targeted NPC to the executing player.
- `/tw settamed` toggles the tamed flag on the targeted NPC.
- `/tw reloadconfig` reloads spawner + naming + command item configs from disk (`TwSpawnerConfig`, `TwNameItemConfig`, `TwCommandItemConfig`).
- `/tw debughook [on|off]` toggles hook debug logging (or explicitly enables/disables it).
- `/tw debugprompt [on|off]` toggles interaction prompt diagnostics (selection + alarm state).
- `/tw debugspawner [on|off]` toggles spawner raycast debug logs.

## Notes
- Components persist across reloads.
- `TameworkHook` can be paired with the `TriggerNpcHook` effect in `TwInteractionConfig` to bridge interaction logic into custom NPC instructions.
- `TameworkInteract` logs a warning when no interaction matches, which is useful for debugging requirements.
- See `docs/Command-Items.md` for command-tool command list schema, selection UI, and relocation behavior.
