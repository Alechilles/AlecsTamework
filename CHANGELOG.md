# Changelog

## 2.1.1 - Linked Companions Panel UX and Safety - 2026-02-21
### Added
- New `CommandLinkedPanelRequireUnlinkConfirm` global setting in `TwGlobalConfig` (default `true`) to control whether unlinking from the linked-companions panel requires a second confirm click.
- Linked-companions panel empty state messaging when no NPCs are linked to the command tool.
- Row-level loaded/unloaded/confirm status badges in the linked-companions panel.
- Linked-companions card action buttons for per-NPC `Recall` and `Set Home`, so individual companions can be managed directly from the side panel without changing the currently selected radial command.
- New dead-linked companion tracking so linked NPC deaths are surfaced as `DEAD` in the linked-companions panel instead of being treated as generic unloaded records.
- New `TwGlobalConfig` options for dead companion recovery:
  - `CommandDeadRespawnEnabled` (default `false`)
  - `CommandDeadRespawnCooldownMs` (default `60000`)
- New `TwGlobalConfig` respawn tuning values:
  - `CommandDeadRespawnFollowRetryDelayMs`
  - `CommandDeadRespawnDistanceClose`
  - `CommandDeadRespawnDistanceNear`
  - `CommandDeadRespawnDistanceMid`
  - `CommandDeadRespawnDistanceFar`
- New `TwGlobalConfig` command placement vertical-band values used by Recall and dead respawn:
  - `CommandPlacementMinRelativeY`
  - `CommandPlacementMaxRelativeY`

### Changed
- Linked-companions rows now show clearer unloaded fallback text (`Unloaded companion (<uuid>)`) and unloaded health messaging.
- Command radial panel subtitle now guides unlink confirmation when a remove action is armed.
- Dead linked companions are no longer treated as generic unloaded targets for Recall/Return Home queueing.
- Dead companion recovery is now explicit via a linked-panel `Respawn` button (shown when cooldown is ready and dead respawn is enabled), and relinks the command tool to the newly spawned NPC.
- Linked-companions panel now updates row health/cooldown/status once per second while open via incremental UI selector updates (no full page rebuild).
- Linked-panel `Respawn` button now uses the shared secondary button style for clearer outline/hover/pressed feedback.
- Dead companion respawns now re-enter follow behavior immediately by clearing combat lock, restoring owner as `MasterTarget`, and applying follow-compatible state fallback.
- Dead companion respawn follow bootstrap now prioritizes `Follow` state first, with `Idle` as fallback.
- `TwGlobalConfig_Default.json` is now organized into top-level sections (`General`, `OwnershipProtection`, `InteractionDefaults`, `Command`), and `TwGlobalConfig` now reads the sectioned schema directly.
- Recall and dead-companion respawn now share the same safe placement pipeline (surface projection + radial candidate sampling) and no longer use a separate recall-only placement path.
- Recall and dead-companion respawn placement candidates are now randomized and sampled off-camera first, so companions no longer consistently appear directly behind the player.

### Fixed
- Dead linked-companion snapshots now persist to plugin data so companions remain `DEAD` (and respawnable after cooldown) across relog/server restart instead of reverting to generic unloaded state.
- Dead companion respawns now sample nearby surface positions and avoid spawning inside terrain blocks in common recall/respawn cases.
- Dead companion respawns now retry follow bootstrap shortly after spawn to avoid race conditions where state/target supports are not yet ready on the first frame.
- Dead companion respawn placement now prioritizes nearby forward-facing in-view points and low-height surface probes, reducing outside-building spawns when the player is indoors.
- Unloaded companion recall is now more reliable after a prior queued Return Home, because relocation source chunk preloading now uses both metadata hints and cached last-known positions without stale hint overwrite.
- Unloaded companions in the linked panel now use cached identity fallback with priority `Display Name > Name Key > Role ID` instead of always showing `Unloaded companion (<uuid>)`.
- Queued per-companion recalls now perform an additional short post-chunk apply probe, which fixes cases where first-click recall only loaded an unloaded companion and required a second click to actually relocate it.
- Linked-panel per-companion `Recall` now reuses the same recall command execution pipeline as radial recall (including loaded/unloaded handling and relocation queue behavior), eliminating drift between command and button outcomes.
- Unloaded recall queueing now falls back to stored home when last-known position is missing (common after relog), so recall can still load source chunks and relocate companions.
- Relocation apply now uses a short burst of retry probes around queue/chunk-load events, improving first-click unloaded recall reliability when NPC components become available a few frames after chunk load.
- Relocation retry accounting is now interval-based (instead of counting every rapid probe), and unloaded recall probes now cover a longer first-load window to reduce missed first-click recalls for companions that materialize slightly later after chunk load.
- Relocation scheduling is now single-flight per NPC with per-chunk request throttling, preventing relocation probe floods that could stall the world thread during unloaded recall failures.

### Notes
- Linked-companions row UI now includes hidden scaffolding for future secondary stats and action buttons (traits/talents), so the panel can be extended without another structural UI rewrite.

## 2.1.0 - Command Items Beta and Asset Loading Fixes - 2026-02-19
### Added
- New command item config asset type **TwCommandItemConfig** under `Server/Tamework/Items/Commands`, plus the `TameworkCommand` item interaction and example command whistle assets.
- Command item runtime support for per-tool linking, selected-command metadata, command-step execution, and command feedback (chat/HUD/sound/particles).
- Command selection radial UI page (`TameworkCommandRadialMenu.ui`) opened via secondary use (`CommandId: OpenSelectionMenu`) with clickable slice buttons.
- New `Component_Tamework_Instruction_Command_Move` instruction bridge for move-to-ping/return-home hooks.
- `TameworkHook` now exposes hook target-position info (`HookHasTargetPosition`, `HookTargetX/Y/Z`) and position-provider support for movement instructions.
- Off-screen command relocation queue service with chunk preload retries, last-known NPC position tracking, and on-load application for unloaded linked NPCs.
- Early asset-pack ordering hook that reorders Tamework directly after `Hytale:Hytale`, replaces conflicting Tamework packs with the plugin jar pack, and removes legacy standalone `Alec's Tamework! (Assets)` packs/archives.
- Command relocation/recall tuning values (hybrid path/teleport thresholds and relocation retry limits) are now configurable via `TwGlobalConfig`.

### Changed
- `Component_Tamework_Instruction_Defend` now delegates non-combat follow behavior through configurable `DefendFollowMacroElement` (defaults to `Component_Tamework_Instruction_Follow_Simple_TP`), so modders can swap in custom follow components without editing Tamework core assets.
- Removed unused legacy parameters from `Component_Tamework_Instruction_Follow_Simple` so the asset only exposes fields that are actually consumed by the simple follow logic.
- Follow seek behavior now tracks the locked `MasterTarget` directly (instead of visibility-dependent player sensors), so follow mode is no longer blocked by grass/occluders.
- `StoreHome` now stores home per NPC in `TameworkCommandLinksComponent` (instead of per-tool metadata), allowing linked NPCs to keep distinct home locations while preserving off-screen return-home relocation support.
- Packaging now embeds `Common/` and `Server/` assets directly in the plugin jar and deploy profiles copy jar-only (no separate `(Assets)` zip).
- Updated Tamework plugin and assets manifests `ServerVersion` to `2026.02.18-f3b8fff95` for latest pre-release server compatibility.

### Fixed
- Corrected `Component_Tamework_Instruction_Defend` instruction structure to avoid defining both `Actions` and `Instructions` on the same instruction node.
- Added `Tamework.Instruction.Follow` interfaces to Tamework follow components and constrained Defend computed follow references to `Hytale.Instruction.Null` or `Tamework.Instruction.Follow` so computed references validate and load correctly.
- HUD popup feedback no longer uses `CustomUIHud`; it now uses the native notification UI channel to avoid custom-HUD ownership conflicts and flicker/crashes from HUD replacement mods.
- Command notifications now use contextual styles: command selection stays `Default`, successful command dispatch uses `Success`, and command dispatch failures use `Warning`.

## 2.0.3 - Example NPC Spawn Fix - 2026-02-18
### Fixed
- Updated Tamework example NPC/template `AttitudeGroup` defaults from `Livestock` to `PreyBig` so `Mob_Tamework_Example` and related examples spawn correctly on current Hytale builds.

## 2.0.2 - Interaction Role Swaps and Naming Items - 2026-02-17
### Added
- Tame interaction option to swap NPC roles after taming via `Role`/`RoleParam`.
- `SetRole` interaction effect for role swaps in any interaction entry.
- New `TwNameItemConfig` asset type under `Server/Tamework/Items/Naming` for naming item rules.
- `TameworkNameNpc` item interaction to start a chat-based naming flow.
- `TameworkNpcNameComponent` to persist custom NPC names and metadata.
- Naming ownership option `AllowUnownedWhenRequireOwner` for owner-or-unowned naming behavior.

### Changed
- Tame checks now treat NPC role ids that start with `Tamed` as tamed for vanilla compatibility (interactions, naming, spawner capture, `TameworkIsTamed`, and `/tw gettamed`).

## 2.0.1 - Pre-release Compatibility Fixes - 2026-02-16
### Fixed
- Spawner capture/spawn now tolerates `CapturedNPCMetadata` getter/setter changes in the pre-release build (prevents `NoSuchMethodError` when resolving roles).
- Interaction floating text/combat text no longer crashes world threads on pre-release builds where `ComponentUpdate` is abstract (uses `CombatTextUpdate` with a compatibility fallback).

## 2.0.0 - Interaction System Overhaul + Global Config - 2026-02-15
### Added
- New **TwInteractionConfig** interaction system with explicit requirements/effects, cooldowns, and configurable priorities.
- Parameterized requirements/effects via role params (ItemsParam, AlarmParam, InteractionContextParam) with role-scope fallback.
- Interaction prompt system (`TameworkInteractPrompt`) with translation keys, prompt selection, and contextual prompt support.
- UI feedback options for interactions: floating text, HUD message overlay (TameworkMessageHud), and mode-cycle messages.
- `AddItemsHand` effect and ItemsParam support for inventory effects.
- Global config asset **TwGlobalConfig** with defaults defined in assets and warnings when fields are missing.
- TameworkHook trigger effect + diagnostics gated by `/tw debughook`, plus `/tw getalarm`.
- Debug toggles for prompts and spawners: `/tw debugprompt` and `/tw debugspawner`.
- Soul lantern shared assets moved into Tamework (spawner example assets updated).
- Build step to copy the jar + assets zip directly to `Hytale\UserData\Mods`.
- Unit tests for interaction parsing, matching, params, cooldowns, and alarms.

### Changed
- Interactions are gated by actual player input; duplicate inputs are deduped by client use time.
- Cooldowns use real time (seconds) and contextual harvest respects cooldowns.
- Interaction config resolution is cached by role and respects Priority overrides.
- Requirements schema updated: ItemsInHand/ItemsInInventory/ItemsEquipped are arrays; equipped slots require arrays.
- Prompt selection prioritizes contextual interactions, refreshes hints on change, detects contextual items, and falls back when contextual entries are blocked.
- Harvest prompt text updated for contextual use (`Use item to harvest`).
- UI message placement/fade tuned for readability.
- Example templates refreshed (state setters for validation, mount anchors, and debug nameplate disabled).

### Removed
- Legacy interaction `Param` aliases.
- Old Tamework settings config file (replaced by TwGlobalConfig assets).

### Fixed
- Harvest prompt gating for alarm readiness (including unset/active handling).
- Alarm evaluation now blocks when world time is unavailable.
- Hook sensor restricted to recent interactions (prevents passive firing).

## 1.2.0 - TwSpawnerConfig Assets + Capture/Spawn Overhaul - 2026-02-09
### Added
- TwSpawnerConfig asset type stored under Server/Tamework/Items/Spawners for spawner item behavior.
- Capture/spawn settings split into Capture and Spawn sections with optional effects (ParticleSystem/SoundEvent) and limits (CooldownMs/MaxDistance).
- Role-scoped icon overrides via IconOverridesByRole alongside default icon overrides.
- Spawner items can capture/spawn using the TameworkSpawn interaction alone (no role interaction chain required), driven by spawner assets.

### Changed
- Allowed roles now default to **Allowlist**; use AllowAll explicitly when needed.
- /tw reloadconfig now reloads spawner config assets from disk.

### Removed
- Legacy Server/Tamework/Tamework_Items_Config.json item config system and per-world overrides.

## 1.1.1 - Hytalor Example + Follow Component Split - 2026-02-06
### Added
- Hytalor patch example assets (Template/Mob + patch) showing non-destructive Tamework integration.
- New follow components split: Follow_Simple_TP (teleport/seek), Follow_Simple (basic follow), and Follow_Advanced (old IdleFollow behavior).
- /tw gettamed and /tw settamed commands to read/flip tamed state.
- Mount gating for tamed/owner state with crouch-based interaction (Hytalor example).

## 1.1.0 - Core Systems Update - 2026-02-03
### Added
- Owner + Tamed components for NPCs, plus new actions/sensors (set owner, set tamed, owner/stranger/wild capture routing).
- Deny capture while untamed action with optional food list hint (resolves item display names when available).
- Per-mod item config discovery via Server/Tamework/Tamework_Items_Config.json.
- Save-world local overrides for item configs (created empty by default).
- Localization discovery that scans both global Mods and save-world mods.
- Owner utility messaging for denied interactions and untamed capture.
- Assets zip build step for standalone asset distribution (contents of resources + LICENSE.txt).

### Changed
- Tamework example templates updated to match the new capture/tame flow (feed to tame, capture only when tamed).
- Settings/config resolution prefers save-world mods when present, with fallback to global mods.
- Owner name resolution now prefers display name, then username, with UUID fallback.
- /tw getowner now prints owner name + UUID when available.

### Fixed
- Mod discovery null-path issues when running in save-world contexts.
- Missing owner names (messages and getowner previously only showed UUIDs).

### Notes
- Defaults now live in Server/Tamework/Tamework_Items_Config.json (no code-driven defaults).
- Local override configs are intentionally created empty so new items/functions from mod updates are not masked.
