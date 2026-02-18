# Changelog

All notable changes to **Alec's Tamework!** will be documented in this file.

## Unreleased
### Changed
- `Component_Tamework_Instruction_Defend` now delegates non-combat follow behavior through configurable `DefendFollowMacroElement` (defaults to `Component_Tamework_Instruction_Follow_Simple_TP`), so modders can swap in custom follow components without editing Tamework core assets.
- Removed unused legacy parameters from `Component_Tamework_Instruction_Follow_Simple` so the asset only exposes fields that are actually consumed by the simple follow logic.
- Command whistle secondary use now opens a clickable command selection wheel page (radial-style layout) for direct command selection.
- Command selection wheel visuals now use dedicated UI ring textures instead of `CircularProgressBar`, eliminating arc artifacts and enabling cleaner circular styling.
- Command wheel command buttons now render as texture-based radial pie slices with hover/pressed states, while command labels render separately around the wheel for readability.
- Command wheel art pack now includes a styled center panel texture and geometry-aligned anchors for slice hitboxes/labels, improving visual alignment of the radial UI.
- Command wheel background ring layers are now disabled by default so the radial slices read more cleanly with less visual noise.
- Command wheel slice textures now use a single-tone dark blue style, and the center status panel texture has been switched from rounded-square to circular.

### Fixed
- Corrected `Component_Tamework_Instruction_Defend` instruction structure to avoid defining both `Actions` and `Instructions` on the same instruction node.
- Added `Tamework.Instruction.Follow` interfaces to Tamework follow components and constrained Defend computed follow references to `Hytale.Instruction.Null` or `Tamework.Instruction.Follow` so computed references validate and load correctly.

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
