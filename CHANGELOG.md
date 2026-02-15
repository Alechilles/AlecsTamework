# Changelog

All notable changes to **Alec's Tamework!** will be documented in this file.

## 2.0.0 - Interaction System Overhaul + Global Config - 2026-02-15
### Added
- New **TwInteractionConfig** system with requirements/effects and interaction test assets.
- Parameterized requirements/effects via role params: ItemsParam (items or items+quantities), AlarmParam, and InteractionContextParam with role-scope fallback.
- Interaction config **Priority** field and role→config cache to control overrides.
- UI feedback options for interactions: floating text + UI message overlay (TameworkMessageHud) and mode cycle messages.
- TameworkHook trigger effect diagnostics and `/tw debughook` toggle; `/tw getalarm` command.
- Unit tests for interaction parsing, matching, params, cooldowns, and alarms.

### Changed
- Interactions are now gated by actual player input; duplicate inputs are deduped by client use time.
- Cooldowns use real time (seconds), and contextual harvest now respects cooldowns.
- Global settings moved to **TwGlobalConfig** assets with defaults defined in assets and warnings when fields are missing.
- Requirements updated: ItemsInHand/ItemsInInventory/ItemsEquipped are arrays; equipped slots require arrays.
- Interaction matching and inventory checks hardened; feed handling and alarm/cooldown resolution centralized.
- UI message placement and fade tuned for readability.

### Removed
- Legacy interaction `Param` aliases.
- Old Tamework settings config file (replaced by TwGlobalConfig assets).

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
