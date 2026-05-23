# Changelog

## 2.11.0-PRERELEASE-0.5-pre.9.1 - Hytale 0.5.0-pre.9.1 Beta Compatibility - 2026-05-22

### Changed
- Prepared this beta build for Hytale `0.5.0-pre.9.1` and updated the plugin metadata to use the new Semver-style server compatibility declaration instead of the legacy date/hash pre-release build string.
- Carried the 2.11 optional asset patching, attachment display-name, and spawner icon tooling work forward onto the Update 5 compatibility branch.

### Fixed
- Fixed Update 5 pre-release compile breaks caused by the removed flying motion `forceVelocity` API and the new keyed `CustomUIHud` constructor requirement.
- Fixed Update 5 startup failures from stricter runtime asset-pack ID parsing by using parseable generated/self-test pack IDs and only registering the writable self-test pack when `/tw patches selftest` runs.
- Cleaned up the pre-release run profile so local server runs install the jar into the pre-release userdata mods folder.

## 2.11.0 - Optional Asset Patches, Attachment Display Names, and Spawner Icon Tooling - 2026-05-21

### Added
- Added `TwAttachmentDisplayConfig` so mods can define player-friendly attachment names once and have captured spawner tooltips show those labels when DynamicTooltipsLib is installed.
- Added optional asset patches so third-party mods can keep base JSON-like assets vanilla-safe, then patch in Tamework NPC behavior, item actions, item configs, particles, projectiles, drops, and other server JSON assets only when Tamework is installed.
- Added `/tw patches status` and `/tw patches reload` so operators can inspect optional asset patch results, regenerate patched outputs, and see which generated targets still require a server restart.
- Added `/tw patches selftest` and `/tw patches selftest cleanup` so operators can generate isolated patch fixtures, exercise the live reload path, and verify which targets hot-reload or require a restart. The self-test now observes Hytale's generated-pack asset reload events for NPC role/template, item, Tamework config, particle, and common targets without using the unsafe synchronous asset-store reload path.
- Added `Mob_Tamework_Example_Patch` as a bundled optional-patch test NPC whose base template stays barebones until `Server/Tamework/Patches` upgrades it with Tamework behavior.
- Added spawner icon batch manifests and generator support for shared override groups, group defaults, replacement runs, excluded attachment options, and auto-framed Blockbench renders.

### Fixed
- Optional asset patch reloads now rely on Hytale's generated-pack watcher for NPC role/template targets instead of manually unloading and reloading generated NPC builders during `/tw patches reload`.
- Fixed the Blockbench spawner icon batch renderer leaving every rendered model open as a separate Blockbench tab during large icon-generation runs.
- Fixed spawner icon generation merging duplicate groups incorrectly and missing batch source assets in larger render sets.
- Fixed captured spawner tooltips and linked companion panels showing tamed role IDs when the role asset points at a different display-name translation key.

### Removed
- Removed the outdated Hytalor patch example assets from the bundled examples now that Tamework has its own optional asset patch system.

## 2.10.1 - Mushroom Spore Crafting Balance - 2026-05-16

### Changed
- Updated `Glowing Purple Mushroom Spores` crafting to produce two spores per craft, letting one mushroom create two plantable seeds.

## 2.10.0 - Flying Mounts (Beta), Role-Line Breeding, and Attachment Migrations - 2026-05-13

### Added
- (beta) Added a custom mounted flight controller
- Added role-line inheritance to `TwBreedingConfig` so offspring can inherit parent body/model variants, use weighted family lines, and optionally mutate into non-parent lines.
- Added role-scoped `TwAttachmentMigrationConfig` assets so mods can backfill newly split attachment slots from legacy stored selections without overwriting already-randomized values.
- Added a public Trait Effects API so integrations can register custom `TwTraitConfig` effect keys and execute them during Tamework's existing trait-effect resyncs.
- Added `Breed.ManualSelectionSeconds` for manual breeding interactions; players now manually select both intended parents, and manual breeding remains available even when passive breeding or the per-NPC breeding toggle is disabled.
- Skipped breeding happiness thresholds for both manual and passive breeding when the happiness system or breeding happiness requirement is disabled.

### Fixed
- Fixed Tamework-managed attachment sync restoring harvestable fur/wool visuals while a harvested NPC is still on cooldown.
- Fixed linked companion panels showing generic base-species labels when a role-specific translation exists, such as body-type cat variants.

## 2.10.0-Update5-Prerelease - Update 5 Pre-Release Compatibility - 2026-05-07

### Changed
- Updated Tamework for Hytale Update 5 pre-release APIs, including the renamed vector package, quaternion rotation helpers, projectile/event signatures, and NPC/component access changes used by companion commands, spawners, naming, interactions, needs, breeding, damage, and persistence flows.
- Updated player hotbar access to read the Update 5 hotbar component, restoring contextual prompts, item-sensitive interactions, command tools, naming items, and spawner item behavior.
- Updated custom NPC display-name handling to write both persistent and runtime display-name components required by Update 5.
- Updated release metadata for the 2026.05.07 Update 5 pre-release server build and marked this build as a beta/prerelease release for platform publishing.

### Fixed
- Fixed startup failure on Update 5 caused by removed `com.hypixel.hytale.math.vector.Vector3d` / `Vector3f` / `Vector3i` classes.
- Fixed Update 5 command/interaction targeting paths that depended on older inventory, rotation, and display-name APIs.
- Fixed Maven test configuration so caller-provided Surefire `argLine` values are preserved while still installing the Hytale log manager for tests.

## 2.9.0 - Companion Controls, Gender Breeding, and Embedded Telemetry - 2026-05-04

### Added
- Added `/tw showspawnmarkers [radius|off]` to render nearby loaded Hytale spawn markers with bright-pink player-local debug shapes and print marker IDs, NPC options, positions, spawn counts, manual-trigger status, and suppression state.
- Added `/tw deletespawnmarker [range]` to delete the closest loaded spawn marker in the player's view path, clear the source block marker component when available, and report the marker ID, NPC options, and position.
- Added optional gender support to `TwBreedingConfig` so modders can require male/female breeding pairs, preserve gender through growth/spawner metadata, show gender in linked panels and spawner tooltips, and use gender-aware random name pools.
- Added a `/tw settings` toggle for companion breeding genders, enabled by default, so server owners can disable gender assignment and gender-aware breeding checks without editing assets.
- Added a `/tw settings` toggle to disable recall/return-home teleporting, hiding Recall actions while disabled, plus a linked-panel Locate action that opens a copyable current or last recorded companion location page.
- Added `TameworkProgressionTimeScales` so integrations can apply per-world progression time multipliers for systems such as breeding timers, with cleanup when worlds are removed.

### Changed
- Switched Tamework to the shared embedded Alec's Telemetry runtime dependency instead of carrying the runtime sources directly in the mod.
- Moved `/tw settings` telemetry persistence into `universe/Tamework/Settings/tamework-settings.json`, with legacy crash telemetry opt-out files imported without deleting them.

### Fixed
- Fixed `/tw npcspawntamed` attachment overrides being replaced by the NPC's initial random attachment choice after the sync system ran.
- Fixed linked companion respawn, lost-recovery, and auto-link record writes so Locate keeps the known world name immediately after a replacement link is created.
- Fixed legacy telemetry settings imports, including old telemetry paths, migration roots, and text boolean values from earlier builds.
- Fixed custom NPC names staying visible while a player is mounted by making mounted-name hiding and restoration self-correct during world ticks.

## 2.8.6 - Breeding Families + Runtime Compatibility - 2026-04-26

### Added
- Added family-based breeding compatibility so related adult roles can breed together, optionally require different adult roles, share a baby role, and persist a weighted future adult role for growth. Existing same-role breeding configs remain valid.
- Added typed telemetry event context for non-crash events so Tamework and downstream integrations can report structured details such as subsystem, phase, operation, target, feature key, runtime side, entity/item/block IDs, command names, and bounded custom detail fields.

### Changed
- Deprecated `Pairing.RequireSameRoleId` in favor of `Pairing.RoleCompatibility`; old configs still read the legacy field, while new defaults, docs, and the config editor use `RoleCompatibility`.
- Updated bundled telemetry routing so Tamework crash/report delivery uses the hosted event ingest endpoint and prefers `eventEndpoint` in embedded project descriptors.

### Fixed
- Fixed player movement speed effects from other mods being applied twice by removing Tamework's player movement effect resync; the base game now remains the only owner for player `HorizontalSpeedMultiplier` movement updates.
- Fixed `/tw showhitboxes` compatibility with Hytale builds where optional debug shape flags are unavailable.
- Fixed typed telemetry event controls, outcomes, and lifecycle details so non-crash telemetry keeps the intended category-specific metadata.

## 2.8.5 - Embedded Telemetry + Flying Companion Grounding - 2026-04-23

### Added
- Added Tamework-managed flying companion landing control support with `TameworkFlyingCompanionComponent`, the `TameworkSetFlyingCompanionMode` action/builder, and a new `FlyingCompanionControlSystem` to help flying companions descend, settle, and hand off into grounded hold states more reliably.
- Added `/tw debugflyingcompanion` plus matching `TwDebugConfig.DebugCommands.FlyingCompanion` support so flying companion landing/grounded handoff diagnostics can be toggled at runtime.

### Changed
- Switched Tamework telemetry over to the bundled embedded Alec's Telemetry runtime so crash, lifecycle, performance, and usage reporting all use one in-jar telemetry path, while preserving the existing `/tw settings` telemetry toggles and `/tw debugcrashtelemetry` tooling.
- Updated the bundled embedded telemetry project descriptor to use Tamework's hosted project key and the `telemetry-dev.alecsmods.com` ingest endpoints while the hosted portal rollout is still under test.

## 2.8.4 - Telemetry Integration + Respawn/Needs Stability - 2026-04-20

### Added
- Added optional Alec's Telemetry integration detection/bridge support plus a bundled telemetry project descriptor for hosted crash, usage, and performance reporting when Alec's Telemetry is installed.
- Added live Alec's Telemetry event emission for `/tw reloadconfig`, `/tw settings`, `/tw news`, and settings-announcement open/review flows, with built-in hosted project defaults for the Tamework telemetry project.
- Added an `Auto-Link` toggle to command-item linked panels so newly tamed companions can automatically bind to the first applicable command item in the player's hotbar or inventory, and breeding offspring can inherit Parent A's command tool binding when their owner is online.

### Changed
- Updated hosted telemetry descriptors/defaults to target the public Alec's Telemetry endpoints instead of local/dev-only endpoints.
- Updated the linked companion command panel to debounce inline text filtering and avoid redundant refresh/binding work during live updates, reducing panel churn with larger companion lists.

### Fixed
- Fixed initial linked companion panel refresh diffing so first-open refreshes no longer do unstable extra redraw work.
- Fixed spawned companions outside breeding inheritance flows losing their initial random attachments when later growing up by seeding stored attachment state during spawn/bootstrap.
- Fixed revived companions carrying stale low-health/needs runtime state after starvation/dehydration deaths and then dying again immediately on respawn.
- Fixed healthy companions still paying the high-frequency natural-regen suppression maintenance cost between normal needs sweeps, reducing unnecessary needs-system work.
- Fixed `/tw debugneedsdamage` spam so diagnostics now only log active suppression/damage work and can flag likely external damage instead of routine healthy ticks.
- Fixed debug telemetry tooling so injected debug events stay constrained to the intended supported telemetry usage flow.
- Fixed command-linked respawn placement safety so revived companions now require real standable clearance and are less likely to respawn embedded in walls.
- Fixed Tamework custom UI localization regressions that could make pages like the group manager show raw `%server...` keys instead of resolved text.

## 2.8.3 - Settings Announcements, Presets, and Needs Diagnostics - 2026-04-13

### Added
- Added a login-time Tamework settings review popup for `/tw settings` access holders, with per-player opt-out tracking and once-per-session dedupe so operators can be re-prompted when a new settings announcement is published.
- Added `/tw news` to reopen the current settings announcement on demand for eligible players, independent of prior opt-out state.
- Added `/tw npcspawntamed` for spawning owned+tamed NPC batches from a requested role id, with optional command-item auto-linking and attachment overrides.
- Added built-in settings announcement localization through `Server/Languages/en-US/server.lang`, with universe config support for either built-in localized copy or raw per-world override text.
- Added `/tw settings` experience presets plus new global toggles for needs, happiness, passive breeding, breeding happiness requirements, and traits so server owners can switch between simplified, easier, and full-experience progression rules without editing assets by hand.
- Added `/tw debugneedsseek` plus throttled seek-target diagnostics so failed hunger/thirst seek passes now report whether they were blocked by need thresholds, missing config/item ids, cached misses, or no resolved water/food target.

### Fixed
- Fixed companion combat target selection so owner hits no longer become retaliation targets during defend/aggressive behavior, including wake-ups from sleep with an owner locked as the target.
- Fixed linked-panel dead companion rows so they can now retain likely death attribution hints such as starvation, dehydration, or the most recent player/NPC killer while keeping revive timing as the primary status text.
- Fixed severe startup hitching around login by skipping redundant companion trait/progression bootstrap work for already-initialized tamed NPCs.
- Fixed dense-NPC interaction prompt lag by caching prompt selections plus repeated settings, path, alarm, item, and requirement lookup work inside prompt evaluation instead of recomputing them every tick.
- Fixed managed coop wild-capture scans doing unnecessary work while coops are roaming, full, or still on capture cooldown, reducing background coop overhead.
- Fixed needs seek scans running too aggressively by gating food/water target searches behind the actual hunger/thirst seek thresholds and caching derived needs config lookups used by the `NeedBelow` sensor.
- Fixed companion water-seeking reliability so dehydrated companions more consistently choose reachable drinking spots near water and can still drink when they are already within water consume range.
- Fixed local singleplayer `/tw settings` and `/tw news` access so the world owner can use them without OP/permission nodes, and added a close-time chat reminder that `/tw settings` can be reopened later.

## 2.8.2 - NameplateBuilder Integration + Health Persistence + Stability - 2026-04-10

### Added
- Added an optional NameplateBuilder integration that overrides the built-in `entity-name` segment with Tamework companion custom names when NameplateBuilder `4.260326.7+` is installed.
- Added optional NameplateBuilder companion segments for happiness, hunger, thirst, tranquilizer status, and traits so players can include companion progression data in their NPC nameplate chains.
- Added optional NameplateBuilder format variants for tranquilizer stacks/time display, shortened companion stat labels, and trait display modes with shortened or full labels plus raw values or linked-panel-style relative percentages.

### Changed
- Updated `Glowing Purple Mushroom Spores` so the seed recipe now also requires one `Glowing Purple Mushroom` in addition to the existing essence cost.

### Fixed
- Fixed startup failure when `SpawningPlugin` is unavailable by treating spawn marker/beacon component types as optional in despawn diagnostics setup.
- Fixed startup crash when SQLite native bindings are unavailable by treating sqlite native linkage failures as recoverable persistence degradation instead of setup-fatal errors.
- Fixed spawner capture/respawn needs restore so companions no longer accrue starvation/dehydration catch-up damage while stored inside capture items.
- Fixed storage-style capture/coop restore flows fully healing companions by persisting current health across stow/release.
- Fixed linked panel captured/offline companion names falling back to raw role ids instead of translated species names.

## 2.8.1 - Post-2.8.0 Stability + Mushroom Spores - 2026-04-10
### Added
- Added `Glowing Purple Mushroom Spores`, a Tier 6 Farming Bench seed recipe that lets players cultivate the mushrooms used for tranquilizer potion crafting.

### Changed
- Updated `Glowing Purple Mushroom` cultivation to use five growth stages, mud-only planting support, and light-sensitive growth modifiers that strongly favor darkness while making direct sunlight nearly stall growth.

### Fixed
- Fixed startup exceptions when duplicate plugin packs are loaded by hardening pack discovery and filtering.
- Fixed needs-damage execution race paths by deferring command-buffer-context damage dispatch, preventing starvation/dehydration death collisions during needs sweep.
- Fixed managed coop resident release placement so the front-of-coop cone follows the coop block's facing and no longer accepts spawn positions more than one block below the coop, preventing releases behind/on top of the coop or into rooms under thin floors.

## 2.8.0 - Settings UI, Crash Telemetry, and Multi-Food Happiness - 2026-04-08
### Added
- Added `/tw settings` UI (`TameworkSettingsPage`) and command wiring with persisted world-level settings storage (`TameworkSettingsStore`).
- Added crash telemetry runtime services (`CrashTelemetryService`, local crash envelope/store, optional HTTP reporting client) with diagnostics/debug command coverage.
- Added base feed-family assets in Tamework: `Tw_Feed_Herbivore` and `Tw_Feed_Carnivore`, including icons/lang keys and global asset-set toggles.
- Added feed impulse mapping support for both item ids and role parameter families (`FeedItemImpulses`, `FeedParamImpulses`) with active-impulse snapshots for linked panel rendering.
- Added needs-damage diagnostics command/toggle coverage (`/tw debugneedsdamage`) for troubleshooting hunger/thirst damage behavior.
- Added `/tw debugspawnerlocation` to isolate spawn-position/raycast diagnostics from general spawner flow diagnostics.
- Added `/tw showhitboxes` to toggle live hitbox/detail-box tracking for the NPC in view (player-local debug rendering).
- Added feed preference resolution helpers/tests for needs-driven container consumption (`FeedItemPreferenceResolver`, regression coverage).
- Added `DamageExecutionWriteSafetyGuardTest` and corresponding contributor guardrail documentation for command-buffer-safe damage execution in runtime system paths.
- Added `StartupResilienceGuardTest` and contributor guardrails for optional setup dependency handling so startup can warn+skip instead of hard-failing.

### Changed
- Reworked companion happiness impulse handling so feed/pet/damage impulses are time-bound, refresh on re-apply, and do not stack as duplicate active buffs.
- Split hand-feed happiness handling from consumed-food impulses (`GainOnFeed` stays hand-feed specific; consumed food uses item/param impulse resolution).
- Updated linked companion tooltip layout and wording to show `Happiness - <current%> -> <target%>`, simplified need modifier labels, and grouped active impulses at the bottom.
- Needs damage is now enabled by default via persisted settings for new worlds/saves and can be toggled in `/tw settings`.
- Updated recipe visibility gating + global config projection to include feed-family toggles and keep toggle-controlled outputs deterministic.
- Updated feed recipes so both `Tw_Feed_Herbivore` and `Tw_Feed_Carnivore` craft `10` items per recipe.
- Simplified `Tw_Feed_Carnivore` crafting input to `10` meat (`Meats`) and removed the fish requirement.
- Added DynamicTooltipsLib to publish metadata optional dependencies.
- Hardened runtime ECS mutation paths so needs/happiness/breeding/mounted-nameplate flows route writes through command-buffer-safe paths during system processing.
- Added architecture safety guard tests and contributor gates to prevent future ECS write-phase and async player-thread-affinity regressions.
- Tranquilizer shortbow adventure-mode fixes were shipped in `2.7.4`; this `2.8.1` section captures the remaining post-`2.7.3` feature set.
- `/tw debugspawner` now focuses on capture/spawn flow diagnostics and logs explicit deny reasons when policy or runtime checks block a request.
- Needs-driven container food consumption now prioritizes higher-value food candidates based on feed impulse preferences instead of consuming the first matching slot.

### Fixed
- Fixed `GainOnPet` and `LoseOnDamage` not being applied by wiring pet-hook and incoming-damage impulse bridges.
- Fixed feed tooltip naming to resolve language keys from canonical consumed item ids (instead of prettified ids).
- Fixed feed impulse tooltip persistence so expired impulses are removed from active display.
- Fixed startup exceptions when duplicate plugin packs are loaded by hardening pack discovery/filtering.
- Fixed additional world-thread crash paths (`Store is currently processing`) in damage/needs/passive-breeding/mounted-nameplate runtime flows by removing direct store writes from system-processing contexts.
- Fixed needs-damage execution race paths in runtime/tick flows by deferring command-buffer-context damage dispatch, preventing starvation/dehydration death collisions during needs sweep.
- Fixed command-linked revivable drop-suppression setup from building a null query input when command-link component types are unavailable during duplicate/partial plugin load states.
- Fixed startup failure when NPC damage drop systems are unavailable by making command-linked revivable drop-suppression system registration optional (warn + skip).
- Fixed startup failure when `Vector3d` is unavailable in constrained runtimes by degrading command-item asset registration/loading gracefully (warn + skip).
- Fixed capture/spawn ownership-requirement evaluation so `RequireOwner` no longer blocks unowned targets/items and still enforces owner match when an owner exists.
- Fixed Soul Lantern/TwSpawner owner checks to consider capture-source ownership metadata during spawn policy evaluation.

## 2.7.4 - Tranquilizer Shortbow Adventure Ammo Fix - 2026-04-06
### Fixed
- Fixed `Weapon_Shortbow_Tranquilizer` failing to fire in `Adventure` mode by replacing inherited crude-arrow primary-shoot ammo handling with tranquilizer-arrow specific primary interactions.
- Fixed tranquilizer shortbow charge-stage ammo flow to match vanilla shortbow stat-driven charge behavior, preventing charge/fail branch desync in adventure combat.

## 2.7.3 - Coop Thread-Affinity Hotfixes - 2026-04-05
### Fixed
- Fixed coop resident release/remap flows that could perform async player component access (`PlayerRef.getComponent(Player)`) from world-tick execution paths.
- Fixed command-linked coop remap fallback behavior by removing global online-player scans from runtime remap logic and limiting remaps to world-safe owner resolution.
- Fixed delayed world-change relocation scheduling to pass player UUIDs across async delay boundaries and resolve live player components only on the world thread.

## 2.7.2 - Config Override Discovery + Breeding Editor Reliability - 2026-04-04
### Added
- Added `/tw npcclean` for role-scoped NPC cleanup in-world.

### Fixed
- Fixed override discovery and reload path handling so configs no longer disappear from mod/global views after apply/reload, including canonical-source and jar-backed source resolution across staging reloads.
- Fixed a coop runtime threading issue by serializing coop tick processing to prevent world-thread concurrent modification failures.
- Fixed breeding config editor behavior for nested role overrides by supporting indexed array-object paths and expanding `RoleOverrides.*.OffspringLifecycle.Families[0].*` fields consistently for staging/reset.

## 2.7.1 - Coop Runtime Gating Hotfix - 2026-04-03
### Fixed
- Fixed a coop disable regression where worlds that previously used Alec's Coops could spawn large numbers of chickens around vanilla coops after Alec's Coops was disabled.
- Managed coop capture/release/sync systems now fully short-circuit when no enabled `TwCoopConfig` assets are loaded, preventing stale coop-ledger releases when managed coops are unavailable.

## 2.7.0 - Config Editor Expansion + Managed Coop Reliability - 2026-04-02
### Added
- Expanded `/tw config` from Global-only to multi-family browsing and editing with mod/type/asset navigation, Local/All mod scoping, and per-section field count summaries.
- Added schema-driven tooltip coverage for Tw config fields and surfaced those tooltips in the in-game config editor.
- Added `TwNamesConfig` support with configurable random-name pools and wired naming-item UI randomize flow.
- Added managed-coop resident role support updates for chicks in coop accepted role lists.

### Changed
- Reworked the config editor property grid to preserve inline asset-editor-style editing while improving section state behavior, source chips, and field staging visibility.
- Updated override-path display and config-editor asset selection UX for clearer per-mod/per-family context.
- Moved default chicken coop config ownership to Alec's Coops content and removed the legacy farming coop config asset.

### Fixed
- Fixed multiple config-editor event/binding issues that caused bad selector payloads, non-updating controls, and unstable apply interactions.
- Fixed config reload/apply reliability issues around staged override loading and stale descriptor resolution.
- Fixed managed coop behavior to eject residents when blocking coop blocks are removed.
- Improved offspring spawn placement safety checks to reduce invalid/unsafe spawn attempts.
- Fixed false coop-removal ejections during login/relog by hardening capture sync checks, missing-block handling, and block-state type normalization.

## 2.6.0 - Public API Phase 2/3 + In-Game Self-Tests + UI Localization - 2026-03-30
### Added
- Added `ProgressionApi` reads and controlled mutation operations for happiness, needs, breeding, traits, lifecycle stage, and attachments with explicit mutation-status outcomes.
- Added `InteractionExtensionsApi` registration lifecycle support for custom interaction requirements/effects/presets through the public API boundary.
- Added an in-game `/tw api test` harness with fixture prep/reset commands and suites for core capabilities, config-resolution checks, progression behaviors, and interaction-extension contract smoke checks.
- Added full `/tw api test` report logging to server output so long test runs are preserved outside in-game chat.
- Added comprehensive localization keys and runtime translation fallback support for command notifications, naming flow, linked companion panel text, command menu/group UI, and the config editor.

### Changed
- Expanded and reorganized wiki/API documentation into nested section indexes, per-family public API references, and scenario-driven API recipe tutorials.
- Updated command and linked-panel UI text resolution to be language-aware through centralized key-based lookups instead of hardcoded English strings.
- Improved combat snapshot debug target/parameter resolution used by debugger and API-facing diagnostics flows.

### Fixed
- Fixed first-run instability in progression self-tests by tightening fixture/setup timing and capability-aware baseline checks so suites complete deterministically.

## 2.5.3 - Experimental Integration API - 2026-03-28
### Added
- Added a public Tamework integration API with stable entrypoint access for downstream mods, including profile reads, command-link reads, policy checks, diagnostics, namespaced profile data, config views, and lifecycle/config events.
- Added a read-only `CommandLinksApi` surface for linked tool ids and saved NPC home-position reads so integrations can inspect stored command-link state without reaching into internals.
- Added modder-facing API documentation covering capabilities, null-safe access patterns, event semantics, and namespaced data rules.

### Changed
- Wired config-family asset reloads into the API event stream so live config reloads surface through the same integration boundary used by downstream mods.
- Expanded the read-only API config surface to companion, interaction, spawner, name-item, command-item, happiness, needs, breeding, and trait families with detached DTO views.

### Fixed
- Fixed role-scoped API config detail serialization so integrations and debugger tooling receive JSON payloads instead of serializer failures on raw `Tw*Config` instances.
- Fixed command-link API reads to resolve saved home-position state from live links, snapshot cache, and persisted capture/death/lost snapshots.

## 2.5.2 - Portal Return Threading Fixes - 2026-03-27
### Changed
- Reverted the experimental SQLite native-library resource renaming workaround and restored standard shaded `sqlite-jdbc` packaging for the release artifact.

### Fixed
- Fixed global owner-population cap checks to count foreign-world ownership on each target world's executor instead of directly iterating cross-world stores from the active world thread.
- Fixed post-portal command linking and legacy first-claim ownership acquisition crashing after a player returned from an instance world while global per-player population caps were enabled.
- Fixed breeding's global per-player population cap path to reuse the same cross-world-safe owner counting helper, preventing the same `Store.assertThread()` failure class in breeding checks.

## 2.5.1 - Persistence Refresh + Portal Instance Hotfix - 2026-03-27
### Changed
- Reworked linked companion persistence onto a shared SQLite profile/snapshot schema across capture, lost, death, and coop tracking, with incremental per-record updates instead of broad snapshot table rewrites.
- Updated legacy persistence import and schema migration paths to populate the normalized profile, alias, tool-link, snapshot, coop-slot, and profile-state tables used by the new runtime.
- Linked companion panel trait indicators now support up to four visible traits and use a wider segmented ring layout with refreshed masking/icon presentation.
- Refreshed the project README and modder quick-start docs to better position Tamework as a framework dependency and point modders to the current documentation flow.

### Fixed
- Fixed cross-world instance transfers reloading TwConfig overrides once per generated world path instead of once per shared universe override root, preventing same-universe portal transfers from stalling the world thread.
- Fixed linked capture and lost-companion persistence paths to upsert/delete only the touched records, reducing stale rewrites and keeping profile updates aligned with the new persistence model.

## 2.5.0 - Coop Runtime Rebuild + Update 4 Stability - 2026-03-26
### Added
- Added a Tamework-authoritative coop runtime for pre-release that manages coop intake/release state through a resident ledger keyed by `world + coop block position + resident slot`.
- Added `TwDebugConfig` asset support (`Server/Tamework/Debug`) to define default `/tw debug...` toggle states that are re-applied on startup and `/tw reloadconfig`.

### Changed
- Promoted all `2.4.6-beta` and `2.4.7-prerelease-beta` changes into stable `2.5.0` (see detailed sections below).
- Overhauled `CommandLinkedNpcCoopService` internals into the authoritative coop-ledger facade used by command panel/status flows, release resolution, and UUID remapping.
- Coop release/capture snapshot handling now restores full resident progression/state payloads (owner/tamed/name, links, attachments, needs/happiness, breeding, traits, and lifecycle state) instead of relying on vanilla replacement heuristics.
- `TwCoopConfig` discovery moved to `Server/Tamework/Items/Coops` for the managed coop runtime model.
- Feed trough food/water charge tracking now uses block component state rather than generated per-charge droplist assets.
- Asset codec decode error handling now uses hardened parsing and silences noisy `decodeJson` stderr spam for expected fallback paths.
- Cross-world companion transfer now defers relocation while NPCs are mounted and handles closed source worlds gracefully.

### Fixed
- Fixed coop cycles that left command panel entries stuck in `UNLOADED` after subsequent night/day capture-release passes.
- Fixed coop resident identity/state drift where re-emerged residents could become effectively new/defaultized NPCs instead of restoring the stored resident state.
- Fixed attachment/progression resets across coop and spawner capture-respawn paths, including needs/happiness persistence.
- Fixed remaining coop attachment pop-in by applying attachment restoration earlier in the load/release path.
- Fixed coop snapshot tracking scope so non-coop NPCs no longer enter coop replacement tracking flows.
- Fixed stale mount-owner references and stale role support references during world/portal transfers to reduce cross-store reference crashes.
- Fixed mounted NPC teleport transfer stability by preserving required mount interaction components across chunk/world moves.
- Fixed portal relocation race paths by dismounting players/NPCs before queued teleport relocation is applied.

## 2.4.7-prerelease-beta - Pre-release 2026.03.23 Compatibility - 2026-03-25
### Changed
- Updated Tamework release target to Hytale pre-release `2026.03.23-338988e70`.
- Feed trough container/state handling now uses pre-release `ItemContainerBlock`-backed block entity container data instead of legacy container state metadata.
- Feed trough block variants now use valid pre-release interaction wiring (`Open_Container`/custom `Use` actions) and usable-state flags so trough interactions trigger correctly.
- Feed trough water/food sync, clear-water interaction flow, and companion-needs trough access paths were updated to use the new container component access model.
- Command/spawner inventory update paths were migrated off removed legacy inventory sync calls and now avoid direct calls to deprecated inventory/player methods removed in pre-release.

### Fixed
- Fixed startup crash from missing `world.meta.BlockStateModule` references in feed trough sync registration.
- Fixed startup asset validation failures caused by removed `Break_Container` root interaction references in feed trough assets.
- Fixed feed trough interaction no-op behavior after startup by migrating block container definitions and usable interaction flags to pre-release-compatible schema.
- Fixed world-thread crashes when linking command tools or remapping linked spawner NPCs due to removed `Inventory.markChanged()` and `Player.sendInventory()` methods.

## 2.4.6-beta - Needs Damage, Inheritance Overhaul, and Claim Guardrails - 2026-03-22
### Added
- Added `TwNeedsConfig.TickPolicy` with owner-presence-aware ticking controls (`Mode`, `OwnerOfflineGraceHours`, `OwnerOfflineDecayMultiplier`) and default owner-online grace policy (`72h`, then normal decay).
- Added `TwNeedsConfig.Damage` controls for starvation/dehydration damage (`Enabled`, `Model`, `DualNeedRule`, per-minute rates, `Lethal`), defaulting to disabled for backward-compatible behavior.
- Added owner activity timeline tracking (`OwnerPresenceTimelineService`) seeded from online players at startup and updated from player connect/disconnect events.
- Added optional `TwGlobalConfig.SimpleClaims` integration sections for breeding claim caps (`LimitPerClaimChunk`, `LimitPerClaimTotal`, `BreedingRequiresClaim`) and tamed-NPC damage guardrails (`ProtectTamedFromNonMembers`, `AllowDamagePermissionKey`).
- Added top-level `TwGlobalConfig.Population` owner-cap settings (`LimitPerPlayerOwnedTotal`, `PerPlayerLimitScope` with `PerWorld`/`Global`) used for breeding and tame acquisition.
- Added new linked-panel ring/icon UI assets and wild-berry-red needs/thought particle assets (`Want_Food_Wild_Berry_Red`, `ThoughtCloud_Wild_Berry_Red`).
- Added optional `Buuz135:SimpleClaims` `1.0.x` dependency declaration in `manifest.json`.

### Changed
- Standardized parent-child inheritance behavior across `Tw*Config` assets to nested-aware object inheritance, with explicit child arrays/maps replacing parent values (no append/union merge).
- `TwBreedingConfig` now keeps `RoleOverrides` local-only (not inherited), while `RoleIds` remain inheritable when omitted; codec tooltips now document this explicitly.
- Needs progression now uses owner-presence effective elapsed time for both decay and needs damage: owner-online windows tick fully, offline grace windows can tick at `0`, and post-grace windows apply the configured offline multiplier.
- Owner damage filtering now applies optional SimpleClaims claim-member/permission checks for tamed targets; denied attacks are cancelled, and claim lookup failures fail-open with throttled warnings.
- Breeding population enforcement now combines SimpleClaims claim caps with new per-player owned-NPC caps at pairing precheck, spawn-time recheck/clamp, and passive-sweep reservation scheduling.
- SimpleClaims breeding claim population counts now include all owned NPCs (`TameworkOwnerComponent.ownerId`), not only breedable NPCs.
- New ownership acquisition gates now block player-driven tame claims (interaction taming/set-owner, legacy first-claim bridge, and spawn-assign-owner item spawns) when the owner population cap is reached.
- Linked companion panel vitals rendering now uses refreshed segmented ring/mask visuals and updated runtime ring-anchor binding for needs + breeding cooldown indicators.

### Fixed
- Passive breeding sweep no longer hard-crashes the world thread when passive sweep carrier classes fail to resolve at runtime; sweep failures are logged and skipped for that interval.
- Needs-damage events now bypass trait damage multipliers by source tag, ensuring starvation/dehydration damage follows configured flat rates.
- Linked companions with dead-respawn enabled no longer drop death loot while linked, preventing revive-loop drop duplication.
- Nearby-panel `Cull` now unlinks companions before death is applied, so cull drops still process as normal and culled companions are removed from linked tool records.

## 2.4.5 - Companion Metadata Recovery + Tooltip Bridge - 2026-03-20
### Added
- Added optional DynamicTooltipsLib integration for spawner items, including captured tooltip metadata (`Name`, `Role`) and per-spawner `TooltipMode` (`Additive`/`Replace`) via `TwSpawnerConfig`.
- Added shared linked-companion state snapshot caching used across link, refresh, death, and lost-companion flows to preserve full recovery metadata.

### Changed
- `/tw reloadconfig` now invalidates and refreshes DynamicTooltipsLib tooltip caches when that optional dependency is loaded.

### Fixed
- Lost companion recovery now restores captured metadata (display name, appearance/skin variants, progression traits/state, and command-state context) instead of spawning defaultized replacements.
- Strict lost recovery now prefers shared state snapshots and death-snapshot respawn reconstruction, with stale-original suppression retained for late original reloads.

## 2.4.4 - Companion Travel + Lost Recovery Reliability - 2026-03-19
### Added
- Added strict linked-companion lost-state tracking (`CommandLinkedNpcLost.dat`) wired to relocation retry exhaustion, with persisted original-to-replacement mappings that suppress stale originals if they reappear later.
- Linked companion panel/menu flows now recognize `LOST` companions, block recall/return-home while lost, and allow `Respawn` to perform strict recovery (replacement spawn + anti-dup stale-original suppression mapping).
- Added role-scoped command travel settings under `TwCompanionConfig.Command.Travel`: `CrossWorldRecallEnabled`, `OnTransferFailure`, `FollowMasterOnWorldChange`, and `FollowMasterOnWorldChangeStateFilter`.
- Added automatic companion travel relocation hooks for player world-entry and same-world teleport arrival events.
- Linked companion panel now supports nearby-only `Release` and `Cull` actions behind the existing confirm flow, with ownership/tamed gating for safety.
- Added feed trough asset support (`Tw_Feed_Trough`) with staged food/water block variants, localized open/empty interaction prompts, and generated icon/model assets.
- Added water-trough charge handling through `TameworkClearFeedTroughWater` and `FeedTroughWaterStateService`, including progressive water-state depletion and clear-to-base behavior.
- Added bucket integration mappings so container/deco buckets can refill feed trough water variants to full state.
- Companion needs hydration can now consume nearby feed trough water charges when available.

## 2.4.3 - Defense + Despawn Diagnostics - 2026-03-19
### Added
- Added shared `Want_Food_Charcoal` particle assets (`Want_Food_Charcoal`, `ThoughtCloud_Charcoal`, and `CharcoalThought.png`) so mods can reuse a blank-thought + base charcoal icon food hint.
- Linked panel NPC cards now include a breeding enable/disable toggle (default `off`), and passive/interaction breeding flows now require this per-NPC toggle to be enabled.
- Added `/tw debugdespawn` overloads to support optional role filters for despawn diagnostics (for example `on Tamed_Rat`, `Rat`, and `clear`).
### Fixed
- Defend instruction threat detection now treats mobs that recently attacked the companion itself as valid retaliatory targets (in addition to mobs that attacked `MasterTarget`), so companions no longer ignore direct incoming hits while defending.
- Added immediate tame-flow diagnostics breadcrumbs for role changes when despawn diagnostics are enabled, improving root-cause tracing for intermittent post-tame removals.
- Tame transitions now detach NPCs from spawn-marker/spawn-beacon ownership and clear spawn-tracking state when they become tamed, preventing cave-spawned companions (especially rats) from being reclaimed and removed after role swap.
- Despawn diagnostics now track all tamed companions (instead of rat-only) and include spawn-marker/spawn-beacon reference presence plus linked marker/beacon UUID context on add/remove events.

## 2.4.2 - Interaction + Lifecycle Reliability - 2026-03-16
### Fixed
- Tamework contextual-use targets remain interactable even when their generic `F` prompt is intentionally hidden.
- `/tw debugprompt` now logs raw harvest-alarm resolution details (`exists`, `set`, `unset`, `active`, `passed`, `ready`, and lookup validity) so prompt diagnostics no longer blur together "missing", "unset", and "not ready" states.
- Partial `TwGlobalConfig` assets that only override unrelated sections (for example `AssetSets`) now retain Tamework's built-in interaction defaults, preventing `HarvestAlarmName` and other core interaction param names from becoming `null` at runtime.
- Offspring lifecycle growth without an adolescent role now treats the adult transition as the next stage for baby scaling and duration splits, so baby companions grow toward `AdultSwitchScale` before swapping to the adult role/stage instead of using adolescent fallback scales.
- Offspring lifecycle role overrides now fall back through matching family entries, so adult-keyed lifecycle overrides apply to the configured baby role too and duplicate baby-key override blocks are no longer required.
- Offspring lifecycle adult scale baselines now resolve from the configured adult role's appearance/model asset instead of the spawned baby model, so bred adults inherit the correct adult body size before `SizeMultiplier` traits are applied.

## 2.4.1 - Mounted Companion Hotfix - 2026-03-15
### Fixed
- Mounted companion name hide/restore now preserves existing `Nameplate` archetype membership during mount role swaps, preventing world-thread crashes when mounting or dismounting Tamework companions with custom names.
- Unnamed mounted companions now skip the temporary name-hide path entirely, preventing mount-time crashes that only reproduced when no name was present before mounting.
- Breeding lifecycle diagnostics such as cooldown application and spawn-success traces are no longer emitted at default `INFO` level during normal gameplay.

## 2.4.0 - Tranquilizer + Command UX + Metrics - 2026-03-14
### Added
- Interaction `Requires` now supports `NpcHealthPercent` checks with comparison operators (`>`, `>=`, `<`, `<=`, `==`, `!=`) against a `0-100` health-percent threshold.
- New NPC sensor builder `TameworkEffectActive` for role instructions/sensors, including optional `MinRemainingSeconds` checks for effect-duration thresholds.
- New tranquilizer combat assets: `Tw_Status_Tranquilized` status effect, `Weapon_Arrow_Tranquilizer` ammo, and a cobalt-based `Weapon_Shortbow_Tranquilizer` with tranquilizer projectile variants.
- New dedicated tranquilizer status particle system `Effect_Tranquilizer` (no poison-face overlay) with a custom purple bubble texture (`Effect_Tranquilizer_Bubble.png`) for `Tw_Status_Tranquilized`.
- Added `TwGlobalConfig.AssetSets` opt-in gates (`TranquilizerShortbow`, `TranquilizerArrow`, `TranquilizerPotion`) plus recipe-visibility reconciliation that removes gated tranquilizer recipes from crafting registries (and restores them when enabled).
- Command item configs now support optional `MaxActive` limits, allowing more linked NPCs than active command recipients (`0` keeps the previous unlimited-active behavior).
- Added HStats integration for anonymous Tamework usage metrics reporting, including server-owner opt-out support through `hstats-server-uuid.txt`.
- Added Tamework-side HStats forwarding for tracked Alec dependency asset packs (Cats, Animal Husbandry, Nametags) when installed and declaring a Tamework dependency.
### Fixed
- Linked panel card `INACTIVE` status indicator now uses the same lower status lane as `CONFIRM REMOVE`, avoiding overlap with trait icons.
- Added shared `Component_Tamework_ActionList_StandUp` to Tamework assets so mods referencing stand-up wake transitions (for example tamed predator templates copied from cat-pet behavior) validate without requiring Alec's Cats assets.
- Added `TranquilizerEffectExpirySyncSystem` to force an explicit `COMPLETE` effect-remove network update for `Tw_Status_Tranquilized` during the expiry window (including already-zero remaining duration), preventing lingering tranquilizer particles on clients after natural expiry.
- Tranquilizer status particles now use dedicated finite-budget spawners (`Tranquilizer_Cloud` + bounded `Tranquilizer_Bubble`) so cloud/bubble visuals self-terminate instead of running indefinitely if an engine-side detach/update is missed.
- Tuned tranquilizer fallback particle budgets so cloud/bubble self-termination aligns much closer to the intended 30s tranquilizer duration when detach updates are missed.
- Further tuned tranquilizer fallback particle budgets from live timing checks (cloud/bubble) to tighten self-termination toward the target 30s window.
- Reworked tranquilizer particles to short pulse-style emissions: `Effect_Tranquilizer` now uses a short system lifespan and burst-like cloud/bubble spawner settings so repeated status-driven emits handle the ongoing look while each individual particle instance self-terminates quickly.
- Tame/role-swap interaction flows now explicitly clear `Tw_Status_Tranquilized` before role change, preventing tranquilizer cloud/bubble particles from persisting after taming (including multi-hit tranquilizer cases).
- `Tw_Status_Tranquilized` no longer applies the poison-style `Hurt` status animation pulse, so tranquilized NPCs keep normal sleep/idle posture unless struck by actual hit impact.
- Mounted NPCs now hide active custom nameplates while ridden and restore the previous/custom name after dismount, preventing overhead names from overlapping rider camera/body views.
- Command item feedback sounds now play as local 2D audio for the player using the item, while nearby other players hear the configured in-world 3D sound.

## 2.3.0 - Linked NPC Panel Modes, Grouping, and UX Refinements - 2026-03-11
### Added
- Command linked panel now supports explicit per-tool modes (`LinkedMode`, `NearbyMode`) with nearby radius controls.
- Linked panel now supports per-tool sorting (`Default`, `Name`, `Species`, `Group`) and filtering (`None`, `Name`, `Species`, `Group`) via dropdown controls.
- Linked panel rows now support active/inactive state toggles; inactive NPCs remain visible and can still receive per-row recall/return-home while being excluded from bulk command dispatch.
- Linked panel cards now show breeding cooldown progress rings with compact real-time countdown tooltip text (`Breeding CD`).
- Added command-group metadata persistence for tools and linked NPC records, including group id + color/name display metadata.
- Added `Manage Groups` flow with create/edit/delete operations, row-level inline edit mode, and color-picker-based group colors.
- Linked NPC cards now include a left-side group indicator tab that reflects assigned group color and opens group assignment.

### Changed
- Linked panel control layout was consolidated and tightened for small-panel readability: mode dropdown in header, nearby radius controls on the subtitle row, and compact sort/filter controls.
- Linked panel title now reflects mode (`Linked NPCs` vs `Nearby NPCs`).
- Group assignment now uses an inline overlay on the main selection panel (no panel-close/page-swap flow), preserving panel context and reducing transition flash.
- Group assignment applies auto-link-then-assign behavior when assigning a non-`None` group to an unlinked owned NPC.

### Fixed
- Species filtering now uses consistent role-id resolution across linked and nearby entries, including legacy fallback handling.
- Resolved multiple CustomUI binding/selector issues in group manager and group-assignment flows (create/close no-op cases, color picker property binding mismatches, and action payload gathering issues).
- Resolved command panel transition/input-lock issues when navigating between main panel and group manager.
- Resolved inline group assignment `Apply` handling so apply events carrying both value + action are processed reliably.
- Group tab indicator alignment and sizing were corrected for card row centering and visibility.

## 2.2.1 - Companion Policy, Interaction QoL, and Config Scope Updates - 2026-03-08
### Added
- New lag diagnostics toggle command: `/tw debuglag [on|off]` for targeted server performance logging.
- Legacy-tamed ownership bridge for mid-playthrough installs: vanilla `Tamed_*` NPCs without Tamework owner data can now be claimed on first eligible owner interaction/link flow.
- Interaction requirement item matching now supports inverse operators (for example `ItemsInHand.Operator: NoneOf`) for custom "wrong item"/"not holding item set" flows.
- Interaction particle effects now support param-driven attachment targeting (`AttachTarget`, `AttachNode`, `OffsetParam`) with optional player-only visibility control.
- New role-scoped companion policy asset type: `TwCompanionConfig` (`Server/Tamework/Companion`), with priority + parent fallback support for ownership protection and command behavior tuning.
- Companion/global command cooldown config now supports `DeadRespawnCooldownMins` as a human-friendly alias, with minutes overriding `DeadRespawnCooldownMs` when both are present.

### Changed
- Added guarded lag-probe logging in command, spawner, and naming item interactions, owner-interaction filtering, owner damage filtering, and command relocation retries/chunk requests when lag diagnostics are enabled.
- `-Prun-server` now supports optional JVM/server argument passthrough properties (`-Dhytale.server.jvm.args` and `-Dhytale.server.extra.args`) for local resource-constrained runs.
- Owner damage filtering and command companion behavior now resolve policy by companion role through `TwCompanionConfig`, with automatic fallback to `TwGlobalConfig` when no role-scoped companion policy is configured.
- Command dead-respawn cooldown windows are now captured per companion role at death-snapshot time (role policy aware) instead of using a single global cooldown.
- `TwGlobalConfig_Default` now only includes truly global settings (`General`, `InteractionDefaults`, command relocation infrastructure + linked-panel unlink confirmation); ownership protection and per-companion command behavior defaults now live under `TwCompanionConfig`.

### Fixed
- Manage Groups create/rename/recolor actions now use live input drafts reliably, fixing cases where group create/edit actions did not apply.
- `Component_Tamework_Instruction_SeekFood_PlayerFollow` now uses valid sensor/filter combinations (no invalid `Player` filter builder), restoring clean NPC builder validation for livestock templates that reference it.
- Interaction particle spawning now uses the vanilla-compatible model-particle packet path for node/entity attachments, with stable world-space positioning.
- Particle tint application now uses packet-level color assignment (instead of unavailable `ModelParticle#setColor`), preventing interaction-triggered runtime errors.

## 2.2.0 - Progression, Coop Integration, and UI/Behavior Refinements - 2026-03-07
### Added
- Foundation breeding and traits asset types: `TwBreedingConfig` and `TwTraitConfig`, including role-priority resolution and default example assets.
- Foundation shared happiness asset type: `TwHappinessConfig`, including role-priority resolution and default example assets.
- Foundation needs asset type: `TwNeedsConfig`, including role-priority resolution and default hunger/thirst config under `Server/Tamework/Needs`.
- New coop integration asset type: `TwCoopConfig` under `Server/Tamework/Farming/Coops`, keyed by `CoopId` with priority-aware selection and parent fallback support.
- New progression components: `TameworkBreedingComponent` and `TameworkTraitsComponent`, plus bootstrap initialization for newly tamed companions.
- New shared progression component: `TameworkHappinessComponent` with config id, value, and last-update timestamp.
- New shared progression component: `TameworkNeedsComponent` with hunger/thirst values, passive-sweep tracking, and applied happiness penalty state.
- New progression component: `TameworkAttachmentsComponent` for persisted model attachment selections used by offspring appearance inheritance.
- Progression persistence bridges for spawner capture/spawn and command-linked death/respawn snapshots (breeding + traits fields).
- Initial `Breed` interaction handling for happiness-gated readiness state setup.
- Unit tests for breeding/traits priority resolution and trait-value metadata codec round-trips.
- Deterministic trait assignment service (`TraitRollService`) with duplicate/conflict enforcement and seed-based rolls.
- Trait modifier resolver service (`TraitModifierService`) for effect-key multiplier lookups from rolled trait values.
- Unit tests for trait roll determinism/conflict handling and trait modifier multiplier resolution.
- `/tw gethappiness` command to inspect targeted NPC happiness source/value and breeding eligibility context.
- `/tw getneeds` command to inspect targeted NPC hunger/thirst state, applied needs penalty, and needs timing/config context.
- `/tw setneeds <hunger> <thirst>`, `/tw sethunger <value>`, and `/tw setthirst <value>` debug commands for direct in-game needs tuning on targeted NPCs.
- `/tw sethappiness <value>` and `/tw gettraits` commands for in-game progression debugging and balancing.
- `/tw settraits <TraitId> <Value> [TraitId Value ...]` and `/tw addtrait <TraitId> <Value>` debug commands for explicit trait assignment on targeted NPCs.
- `/tw setbreedingready [true|false|toggle]` debug command to force/clear breeding readiness on the targeted NPC (including cooldown clear when forcing ready).
- New life-stage progression component/service (`TameworkLifeStageComponent`, `CompanionLifeStageService`) plus `/tw getlifestage` debugging command.
- `/tw findnpc <uuid> [mark:on|off]` command to resolve a specific NPC by UUID, print live world state, and optionally mark its location with particles.
- Unit tests covering trait inheritance blending/mutation bounds and life-stage transition/scale interpolation behavior.

### Changed
- Plugin bootstrapping now registers breeding/traits asset stores and component codecs.
- Coop capture-crate intake now supports optional Tamework policy overlays when a matching `TwCoopConfig` exists, while still using vanilla coop admission gates (`tryPutResident`) for resident-capacity/species acceptance parity.
- Interaction/docs coverage updated to reflect current breeding behavior (no longer a pure stub path).
- Interaction requirement buckets now support `PlayerHandEmpty` for explicit empty-hand gating.
- Interaction `Requires.Parameter` matching now evaluates all resolved role scopes (role/global/exec/sensor fallback) instead of only a single scope, fixing false negatives for valid params such as boolean flags.
- Trait bootstrap now backfills empty trait components with deterministic rolls and role-config IDs.
- Feeding interactions now apply shared happiness gains from happiness config (or shared defaults when no happiness config resolves), including trait-based `HappinessGainMultiplier` scaling.
- Feeding interactions now also apply manual needs refill (`hunger`, plus optional `thirst` for configured water-bucket items), while the new `CompanionNeedsSystem` handles periodic hunger/thirst decay, passive container feed, passive water drinking, and needs-driven happiness penalties.
- Needs progression now supports sensor-driven resource seeking: low-thirst companions can target adjacent drink positions near water, low-hunger companions can target adjacent eat positions near food containers using role `FoodItemIDs`, and `Component_Tamework_Instruction_Needs_Seek_Resource` now enters seek states through needs/resource sensors rather than hook triggers.
- Needs passive refill and needs-resource seek scans now support configurable vertical search depth via `PassiveRefill.ContainerVerticalScanRadius` and `PassiveRefill.WaterVerticalScanRadius`.
- Breeding config resolution now supports direct config-id lookup (`resolveById`) and shared resolver usage across breeding/spawner/respawn paths.
- Happiness updates now route through shared `CompanionHappinessService` (with `BreedingHappinessService` retained as a compatibility shim), keeping architecture open for non-breeding consumers.
- Equilibrium happiness modifiers now support population bands (`Modifiers.Population`) with configurable nearby same-type radius and count ranges, enabling lonely/social/crowded offsets.
- Disposition (`HappinessGainMultiplier`) now scales all happiness deltas and equilibrium modifiers: positive contributions scale directly by disposition, while negative contributions are inversely scaled so high disposition softens detractors and low disposition amplifies them.
- Spawner metadata and command death/respawn snapshots now persist shared happiness state and restore breeding happiness from that shared source for backward-compatible migration.
- Breed interaction eligibility now evaluates effective fertility using trait key `FertilityMultiplier` plus optional interaction `FertilityBonus` before readiness is set.
- Breed interaction eligibility now enforces `TwBreedingConfig.Eligibility.RequireNotSleeping` and `RequireNotInCombat` state gates in addition to tame checks.
- `TwInteractionConfig_Example` now includes a crouch-gated owner `Breed` entry for `Mob_Tamework_Example` so breeding readiness can be tested in-game.
- Breeding now performs nearby partner matching and applies a staged sequence (parent approach movement -> hearts particles -> delayed offspring spawn).
- Breeding parent approach now prefers role-driven pair movement via `Tamework.Breeding.Pair.Start` + `BreedPair` state hooks, with proximity-gated hearts/spawn and fallback direct move for roles without pair-state support.
- Breeding pair movement now keeps parents in pair state briefly after arrival (about 1s) before resetting, and offspring spawn delay after hearts is slightly longer for clearer pacing.
- Breeding hearts trigger sooner after pair arrival by using faster proximity checks and a less strict pairing-ready distance threshold.
- Offspring spawning now supports role baby-variant selection with fallback juvenile scaling, trait inheritance via `TraitInheritanceService`, and alarm-backed parent/offspring breeding cooldown locks.
- Breeding inheritance now supports deterministic random attachment inheritance with weighted parent/random selection and mutation (`TwBreedingConfig.Inheritance.AttachmentInheritance`), and offspring attachment selections now persist and reapply across runtime role/model transitions.
- Breeding cooldown and offspring lifecycle growth durations now use game-time timestamps with configurable conversion basis (`TwBreedingConfig.Timing.Basis`), while remaining authored in human-readable seconds.
- Adult breeding gates now use explicit life-stage progression when present (with role-name fallback), and life-stage state now persists through spawner capture/spawn and command death/respawn snapshots.
- Breeding offspring spawn now retries nearby fallback positions and uses additional role-resolution fallbacks to prevent silent no-spawn outcomes after successful pairing/hearts.
- Breed interaction custom effects now apply only when breeding actually starts, preventing false-positive "ready" feedback when blocked (for example by cooldown).
- `/tw gethappiness` now reports breeding cooldown state (`cooldownActive`, `cooldownRemainingMs`, `cooldownUntilMs`) and `readyNow` to clarify readiness vs cooldown.
- Offspring spawn diagnostics now include async failure logging, and newborns spawn slightly higher with child hearts for easier visual confirmation.
- Non-baby-variant life-stage scaling now uses smaller juvenile defaults again (`baby=0.55`, `adolescent=0.80`) now that juvenile model-visibility issues were fixed.
- Breeding offspring placement now prioritizes wider horizontal offsets and higher vertical fallback offsets to reduce parent-overlap/terrain clipping.
- Breeding heart-particle spawn now resolves NPC role-template `ParticleOffset` when available (with fallback to default offset), so vanilla and third-party templates can control breeding particle placement.
- Juvenile model scaling now preserves random attachment IDs from the current model when rebuilding scaled models, fixing invisible offspring where required attachments were being dropped during scale updates.
- `Template_Tamework_Example`/`Template_Tamework_Example_Vanilla` now use wander-driven `Idle` behavior and an explicit `Follow` state, and the example command item now sets `Follow`/`Recall` to state `Follow`.
- Breeding offspring progression now creates a vanilla family flock (new flock per breeding family), assigns one parent as the flock anchor, and moves the other parent plus offspring into `FlockFollow` behavior using a new `Component_Tamework_Instruction_Flock_Follow_Wander` asset (vanilla leash-to-leader + local wander).
- Linked companions panel cards now render up to three rolled trait indicators with icon glyphs and directional ring-fill progress (counter-clockwise green for above-default values, clockwise red for below-default values).
- Trait indicator icons in the linked companions panel now show hover tooltips with full trait names and normalized value context (`current / max` plus percent of max).
- Trait-based max-health scaling is now wired through `MaxHealthMultiplier`, applying/removing a dedicated health-max stat modifier during progression bootstrap, offspring initialization, and command respawn restoration.
- Existing companions with persisted traits now have trait stat modifiers resynced on world/entity load via `CompanionTraitStatSyncSystem`, so health trait effects no longer require retriggering tame/bootstrap flows.
- Linked companions panel cards now keep health as a bar and render happiness/hunger/thirst as compact circular progress meters with hover tooltips, reducing card crowding while preserving quick-status visibility.
- Linked companions panel needs rings now render texture icons (happiness/food/water) instead of letter glyphs, preserving 32x32 source art while displaying icons at compact ring-center size.
- Linked companions panel cards now render health value text directly over the health bar (`current/max`) using a centered overlay for better space usage and readability.
- Linked companions panel card container spacing has been tightened (reduced card height/top offset) to remove excess blank space between card content and the next entry.
- Linked companions panel now keeps framed card-row backgrounds while removing the list viewport frame to avoid a nested/double-layered panel look.
- Linked companions panel list top inset has been reduced so the first card sits closer to the subtitle/separator area, matching side inset spacing more closely.
- Linked companions trait rings now support optional texture icons from trait config (`TwTraitConfig.Traits[].IconPath`); trait icon glyph fallback remains in place when no icon path is configured.
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
- Tamework's embedded jar asset pack now registers into AssetEditor's data-source map when missing, so Tamework assets appear in the pack dropdown as read-only and can be duplicated into custom packs.
- Fixed a world-thread crash in `CommandLinkedNpcDeathService` when recording death snapshots for linked NPCs that had neither shared happiness nor breeding happiness (null-safe fallback now avoids `Double` auto-unboxing).
- Tamed companions and companions with persisted custom names are now opted out of vanilla overpopulation despawn checks, preventing accidental despawn of owned/named NPCs.
- Needs-seek runtime now immediately exits to parent completion state when no readable seek target is present, preventing companions from getting stuck in `NeedsSeekFood.Default` / `NeedsSeekWater.Default`.
- Needs-seek movement now uses pathfinder by default and aborts immediately when nav reports `Defer`, preventing companions from remaining stuck against blocked/unreachable resource targets (for example encased food containers).
- Needs-seek now applies a failed-seek cooldown timer (`NeedsSeek_Failed_Cooldown`) and sensor-side cooldown gating, preventing rapid re-entry loops into needs-seek states when nearby targets repeatedly fail pathing.
- Fixed breeding pair state completion flow so `BreedPair` no longer stalls after arrival, and breeding pair start now clears lingering status posture animation to prevent slide-like movement.
- Breeding pair completion now uses timer-driven exit flow (arrival delay + move-timeout fail-safe), and close-range completion follows `SeekStopDistance + 0.15` for tight pair-up spacing without getting stuck in `BreedPair`.
- Tamed companions now self-heal missing/invalid shared happiness state on world load (including `NaN`/non-finite values), so reloads no longer leave companions reporting "no tracked happiness state."
- Companion progression bootstrap-on-load now waits for both NPC + `TameworkTamedComponent` before running, fixing a load-order race where some tamed companions could skip happiness/progression bootstrap after reload.
- Companion progression bootstrap-on-load now defers work through `CommandBuffer.run(...)`, preventing chunk-load crashes from direct `Store.putComponent(...)` calls during store-processing callbacks.
- Breeding offspring progression now bootstraps tamed newborn progression immediately after spawn, ensuring shared happiness state exists at birth instead of appearing as unavailable until a reload heal pass.
- Passive needs ticks no longer apply manual feed gains each sweep; manual needs refill remains interaction-driven only.

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
- `Component_Tamework_Instruction_Follow_Simple_TP` now separates owner lock-on range (`FollowLockOnRange`) from seek threshold (`FollowSeekRange`) so simple follow reliably teleports when the owner is outside seek distance.
- `Component_Tamework_Instruction_Follow_Advanced` now separates seek threshold (`FollowSeekRange`) from teleport fallback so teleport can execute reliably when the owner is outside seek distance.
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

## 1.1.1 - Follow Component Split - 2026-02-06
### Added
- New follow components split: Follow_Simple_TP (teleport/seek), Follow_Simple (basic follow), and Follow_Advanced (old IdleFollow behavior).
- /tw gettamed and /tw settamed commands to read/flip tamed state.

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
