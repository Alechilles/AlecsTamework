# Changelog

## Unreleased
### Added
- Foundation breeding and traits asset types: `TwBreedingConfig` and `TwTraitConfig`, including role-priority resolution and default example assets.
- Foundation shared happiness asset type: `TwHappinessConfig`, including role-priority resolution and default example assets.
- New progression components: `TameworkBreedingComponent` and `TameworkTraitsComponent`, plus bootstrap initialization for newly tamed companions.
- New shared progression component: `TameworkHappinessComponent` with config id, value, and last-update timestamp.
- Progression persistence bridges for spawner capture/spawn and command-linked death/respawn snapshots (breeding + traits fields).
- Initial `Breed` interaction handling for happiness-gated readiness state setup.
- Unit tests for breeding/traits priority resolution and trait-value metadata codec round-trips.
- Deterministic trait assignment service (`TraitRollService`) with duplicate/conflict enforcement and seed-based rolls.
- Trait modifier resolver service (`TraitModifierService`) for effect-key multiplier lookups from rolled trait values.
- Unit tests for trait roll determinism/conflict handling and trait modifier multiplier resolution.
- `/tw gethappiness` command to inspect targeted NPC happiness source/value and breeding eligibility context.
- `/tw sethappiness <value>` and `/tw gettraits` commands for in-game progression debugging and balancing.
- `/tw settraits <TraitId> <Value> [TraitId Value ...]` and `/tw addtrait <TraitId> <Value>` debug commands for explicit trait assignment on targeted NPCs.
- `/tw setbreedingready [true|false|toggle]` debug command to force/clear breeding readiness on the targeted NPC (including cooldown clear when forcing ready).
- New life-stage progression component/service (`TameworkLifeStageComponent`, `CompanionLifeStageService`) plus `/tw getlifestage` debugging command.
- `/tw findnpc <uuid> [mark:on|off]` command to resolve a specific NPC by UUID, print live world state, and optionally mark its location with particles.
- Unit tests covering trait inheritance blending/mutation bounds and life-stage transition/scale interpolation behavior.

### Changed
- Plugin bootstrapping now registers breeding/traits asset stores and component codecs.
- Interaction/docs coverage updated to reflect current breeding behavior (no longer a pure stub path).
- Trait bootstrap now backfills empty trait components with deterministic rolls and role-config IDs.
- Feeding interactions now apply shared happiness gains from happiness config (or shared defaults when no happiness config resolves), including trait-based `HappinessGainMultiplier` scaling.
- Breeding config resolution now supports direct config-id lookup (`resolveById`) and shared resolver usage across breeding/spawner/respawn paths.
- Happiness updates now route through shared `CompanionHappinessService` (with `BreedingHappinessService` retained as a compatibility shim), keeping architecture open for non-breeding consumers.
- Spawner metadata and command death/respawn snapshots now persist shared happiness state and restore breeding happiness from that shared source for backward-compatible migration.
- Breed interaction eligibility now evaluates effective fertility using trait key `FertilityMultiplier` plus optional interaction `FertilityBonus` before readiness is set.
- Breed interaction eligibility now enforces `TwBreedingConfig.Eligibility.RequireNotSleeping` and `RequireNotInCombat` state gates in addition to tame checks.
- `TwInteractionConfig_Example` now includes a crouch-gated owner `Breed` entry for `Mob_Tamework_Example` so breeding readiness can be tested in-game.
- Breeding now performs nearby partner matching and applies a staged sequence (parent approach movement -> hearts particles -> delayed offspring spawn).
- Breeding parent approach now prefers role-driven pair movement via `Tamework.Breeding.Pair.Start` + `BreedPair` state hooks, with proximity-gated hearts/spawn and fallback direct move for roles without pair-state support.
- Breeding pair movement now keeps parents in pair state briefly after arrival (about 1s) before resetting, and offspring spawn delay after hearts is slightly longer for clearer pacing.
- Breeding hearts trigger sooner after pair arrival by using faster proximity checks and a less strict pairing-ready distance threshold.
- Offspring spawning now supports role baby-variant selection with fallback juvenile scaling, trait inheritance via `TraitInheritanceService`, and alarm-backed parent/offspring breeding cooldown locks.
- Adult breeding gates now use explicit life-stage progression when present (with role-name fallback), and life-stage state now persists through spawner capture/spawn and command death/respawn snapshots.
- Breeding offspring spawn now retries nearby fallback positions and uses additional role-resolution fallbacks to prevent silent no-spawn outcomes after successful pairing/hearts.
- Breed interaction custom effects now apply only when breeding actually starts, preventing false-positive "ready" feedback when blocked (for example by cooldown).
- `/tw gethappiness` now reports breeding cooldown state (`cooldownActive`, `cooldownRemainingMs`, `cooldownUntilMs`) and `readyNow` to clarify readiness vs cooldown.
- Offspring spawn diagnostics now include async failure logging and post-spawn presence/position checks, and newborns spawn slightly higher with child hearts for easier visual confirmation.
- Non-baby-variant life-stage scaling now uses smaller juvenile defaults again (`baby=0.55`, `adolescent=0.80`) now that juvenile model-visibility issues were fixed.
- Breeding offspring placement now prioritizes wider horizontal offsets and higher vertical fallback offsets to reduce parent-overlap/terrain clipping, and spawn presence checks now run at multiple delayed checkpoints (0.9s, 3s, 8s) to expose delayed despawn issues.
- Juvenile model scaling now preserves random attachment IDs from the current model when rebuilding scaled models, fixing invisible offspring where required attachments were being dropped during scale updates.
- `Template_Tamework_Example`/`Template_Tamework_Example_Vanilla` now use wander-driven `Idle` behavior and an explicit `Follow` state, and the example command item now sets `Follow`/`Recall` to state `Follow`.
- Breeding offspring progression now creates a vanilla family flock (new flock per breeding family), assigns one parent as the flock anchor, and moves the other parent plus offspring into `FlockFollow` behavior using a new `Component_Tamework_Instruction_Flock_Follow_Wander` asset (vanilla leash-to-leader + local wander).
- Linked companions panel cards now render up to three rolled trait indicators with icon glyphs and directional ring-fill progress (counter-clockwise green for above-default values, clockwise red for below-default values).
- Trait indicator icons in the linked companions panel now show hover tooltips with full trait names and normalized value context (`current / max` plus percent of max).
- Trait-based max-health scaling is now wired through `MaxHealthMultiplier`, applying/removing a dedicated health-max stat modifier during progression bootstrap, offspring initialization, and command respawn restoration.
- Existing companions with persisted traits now have trait stat modifiers resynced on world/entity load via `CompanionTraitStatSyncSystem`, so health trait effects no longer require retriggering tame/bootstrap flows.
- Linked companions panel cards now display a happiness bar (with loaded values from shared happiness state and clear unavailable messaging for dead/unloaded/captured companions).
- Trait roll count per spawn now supports weighted variable outcomes (`Selection.RollCountWeights` for 0..4), with default weighting centered on 2 rolls and 4-roll outcomes kept rare.
- Trait selection config has been simplified to remove stacked fallback layers: `Selection.MaxTraitsPerNpc` is now the single count/cap control, duplicate handling is unified under `Selection.AllowDuplicateTraits`, and legacy `Selection.RerollDuplicates` + `Stacking` fields are removed.
- Trait inheritance now supports per-trait inheritance weighting (`Traits[].InheritanceWeight`) and same-direction parent alignment range bias (`Inheritance.PairAlignmentRangeInfluence`) so high-high or low-low pairings can push offspring values further toward configured trait bounds.
- Trait schema now separates natural-spawn vs breeding ranges (`Traits[].NaturalMin/NaturalMax` and `Traits[].BreedingMin/BreedingMax`): spawn/non-inherited rolls use natural bounds, while inherited/mutated rolls and trait UI "max possible" calculations use breeding bounds.
- Added baseline trait definitions for swiftness, toughness, strength, and foraging luck in the default trait config.
- Swiftness now applies runtime move-speed effects by snapping `MoveSpeedMultiplier` trait values to quantized `Entity/Effects` tiers (`Tw_Trait_MoveSpeed_###`).
- Renamed the harvest double-drop trait display name from `Foraging Luck` to `Bounty` for clearer harvest-output semantics.
- Added baseline `Size` trait definition in default trait config (`SizeMultiplier`) so life-stage/adult scale can roll from trait progression.
- Damage processing now applies trait multipliers for both incoming (`DamageTakenMultiplier`) and outgoing (`DamageDealtMultiplier`) damage.
- Harvest interactions can now trait-proc double drops via `HarvestDoubleDropChanceMultiplier` (interpreted as `chance = clamp(multiplier - 1, 0..1)`).
- Template-driven harvest drops now use `TameworkHarvestDrop`, enabling Bounty trait bonus drops in `$Harvest` state flows (not only interaction `Effects.DropItem`).

### Fixed
- Fixed a world-thread crash in `CommandLinkedNpcDeathService` when recording death snapshots for linked NPCs that had neither shared happiness nor breeding happiness (null-safe fallback now avoids `Double` auto-unboxing).
- Fixed breeding pair state completion flow so `BreedPair` no longer stalls after arrival, and breeding pair start now clears lingering status posture animation to prevent slide-like movement.
- Breeding pair completion now uses timer-driven exit flow (arrival delay + move-timeout fail-safe), and close-range completion follows `SeekStopDistance + 0.15` for tight pair-up spacing without getting stuck in `BreedPair`.
- Tamed companions now self-heal missing/invalid shared happiness state on world load (including `NaN`/non-finite values), so reloads no longer leave companions reporting "no tracked happiness state."

## 2.1.3 - Naming UI and Command UI Polish - 2026-02-24
### Added
- New `TameworkNameInputPage` custom UI used by naming items for direct in-page text entry with `Apply` / `Cancel` actions.

### Changed
- Naming items now open the naming UI by default instead of requiring chat input; when the page cannot be opened, naming safely falls back to the legacy chat prompt flow.
- Name input UI now uses vanilla-style decorated container framing with compact action buttons.
- Linked companions side panel now uses vanilla-style decorated container framing and framed list/card surfaces.
- Command radial slices, linked panel action icon buttons, and naming action buttons now use standard button hover/click sounds for consistency with base UI behavior.

## 2.1.2 - Wander and Ambient Behavior Refinements - 2026-02-23
### Added
- New `Component_Tamework_Instruction_Wander` component for reusable random wandering within a configurable radius.
- New `Component_Tamework_Instruction_Ambient_Idle` component that centralizes reusable ambient posture/flavor/sleep behavior for idle-style states.
### Changed
- `Component_Tamework_Instruction_Wander` now explicitly supports `AvoidBlockDamage` (default `true`) so wander motion can avoid environmental-damage blocks (for example, fire/brambles when flagged as damaging).
- `Component_Tamework_Instruction_Hold` is now a thin wrapper that initializes sleep gates and delegates ambient behavior to `Component_Tamework_Instruction_Ambient_Idle`.
- `Component_Tamework_Instruction_Wander` now supports move/settle cycles and can run ambient posture/flavor/sleep behavior during settle windows (including optional sleep transitions).
- `Component_Tamework_Instruction_Wander` now supports optional settle-exit transition animation + delay (`SettleExitAnimation`, `SettleExitDelayRange`) to prevent posture sliding when resuming movement.
- `Component_Tamework_Instruction_Wander` now re-rolls move vs settle choice on each `.Default` pass so settle/flavor branches are consistently reachable during wander.
- `Component_Tamework_Instruction_Ambient_Idle` now uses shorter ambient reroll windows with flavor cooldown gating so hold/settle behaviors do not chain flavor animations back-to-back indefinitely.
- `Component_Tamework_Instruction_Wander` now enters settle through an explicit posture-entry stage (`.SettleEnter`) so settle posture is applied consistently before ambient settle logic runs.
- `Component_Tamework_Instruction_Hold` now explicitly reapplies hold posture on Hold entry instead of relying on shared ambient one-shot posture initialization.

## 2.1.1 - Linked Companions Panel UX and Safety - 2026-02-21
### Added
- Linked Companions side panel with per-NPC actions: `Recall`, `Set Home`, `Return Home`, and `Unlink`.
- Dead companion state tracking with cooldown-based `Revive` action from the linked panel.
- New command/dead-recovery config options in `TwGlobalConfig` (unlink confirmation, revive enable/cooldown, respawn/follow retry tuning, and placement tuning).

### Changed
- Linked panel rows now refresh health, death cooldown, and status in-place once per second while open.
- Linked panel actions now use compact icon buttons with tooltips, including a dedicated `Revive` icon state.
- Unloaded companion naming now uses cached identity fallback priority: `Display Name > Name Key > Role ID`.
- `TwGlobalConfig_Default.json` is now grouped into top-level sections (`General`, `OwnershipProtection`, `InteractionDefaults`, `Command`).
- Recall and Revive now share the same safe-placement pipeline with off-camera-biased randomized sampling.

### Fixed
- Linked-panel icon textures now load correctly from `Common/UI/Custom/Tamework` (no missing-texture placeholders).
- Dead companion snapshots now persist across relog/server restart and remain revivable after cooldown.
- Revived companions now spawn more safely and reliably re-enter follow behavior.
- Unloaded recall/rehome flow is now consistent and more reliable after relog, including first-attempt recall in previously inconsistent cases.
- Relocation queueing/probing was hardened to prevent world-thread stalls from retry floods.

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
