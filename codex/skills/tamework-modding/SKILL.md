---
name: tamework-modding
description: Use for general Tamework Java/JSON wiring that is not owned by a focused Tamework skill, especially custom NPC builder types, type registration, stable IDs, role templates, and current-source or exact-profile type discovery. Also use to route an unfamiliar Tamework task to its owning skill.
---

# Tamework Modding

Use this skill for registration and wiring work, then load each focused skill
that owns part of the change.

## Identify Scope

1. Resolve the mod root first.
   - Prefer the current workspace when it contains
     `src/main/java/com/alechilles/alecstamework`.
   - Otherwise use
     `C:\Users\22ale\AppData\Roaming\Hytale\Modding\alecstamework` for source
     edits.
   - The shared local runtime is
     `C:\Users\22ale\AppData\Roaming\Hytale\Modding\run\mods\Alechilles_Alec's Tamework!`.
     Treat `UserData\Mods` as a legacy comparison location, not the default runtime.
2. Classify the request.
   - `Integration`: consume existing Tamework features from another mod's
     assets.
   - `Behavior change`: route to the owning domain skill below.
   - `New type`: add a new action/sensor/filter/component/asset family.

## Route Focused Work

- `Tw*Config` schema, inheritance, override, editor, resolver, cache, or reload:
  `$tamework-config-authoring`.
- `TwInteractionConfig` prompt, sensor, action, state, or cooldown lifecycle:
  `$tamework-interaction-configurator`.
- Command items, hotswaps, radial controls, panels, target authority, or HUD:
  `$tamework-command-runtime`.
- Needs, happiness, breeding, traits, talents, levels, or life stages:
  `$tamework-companion-progression`.
- Public API, capability, result, event, version, self-test, or compatibility:
  `$tamework-api-evolution`.
- ECS, world-thread, async, tick, cadence, hot-path, or shutdown work:
  `$tamework-runtime-safety`.
- Avatar flight input, movement, model, rider, equipment, effects, or cleanup:
  `$tamework-avatar-flight`.
- Saved companion state, the companion index, snapshots, capture, store or
  restore flows, or 3.x/4.x import: `$tamework-persistence`.

Load more than one focused skill when a change crosses boundaries.

## Load Registration References

- Read `references/type-discovery.md` for the current-source discovery
  procedure.
- Read `references/asset-paths-and-resolution.md` only for path discovery. Use
  `$tamework-config-authoring` for any config-family change.
- When modifying builder registrations, also read:
  `C:\Users\22ale\AppData\Roaming\Hytale\Modding\alecstamework\src\main\java\com\alechilles\alecstamework\npc\TameworkNpcBuilderRegistrar.java`

## Execute Change Pattern

1. For NPC JSON or asset changes, follow the `hytale-asset-tools` skill (exact
   profile, `author options` over remembered builder IDs, affected-scope
   candidate validation).
2. Edit all required layers, not just one file.
   - For new NPC action/sensor/filter types, update the builder class, runtime
     class, builder registrar, and docs.
   - For new item interaction types, update the interaction class and
     `Interaction.CODEC.register(...)` in `Tamework.java`.
3. Preserve stable IDs.
   - Treat action/sensor/filter `BUILDER_ID`, interaction type names, component
     names, and asset paths as API.
   - Avoid renaming existing IDs unless explicitly requested as a breaking
     change.

## Apply Tamework-Specific Rules

- When a change depends on vanilla Hytale semantics, APIs, gamedata, client UI,
  or javadocs, get the base-game evidence from rune-kb (`kb_search`,
  `kb_symbol`, `kb_asset`; `kb_ui` for `.ui`) before designing the
  Tamework-side edit.
- For runtime reports, compare source repo and `Modding\run\mods` before assuming the game loaded the edited files. Use `UserData\Mods` only when diagnosing a legacy/manual install.
- Treat current Java registration plus the exact project profile as authority.
  A skill reference, old asset, shipped example, runtime copy, or remembered ID
  is discovery evidence only.

## Validate Before Finalizing

1. Run Java tests when code changes are made from the shared workspace:
`bash ../gradlew -p .. :alecstamework:test`
   Use `bash ../gradlew -p .. stageAllModAssets` to stage configured Java mods
   and linked asset packs into `Modding\run\mods`; do not use `runAllMods` for
   staging, because it launches a Hytale server.
   Launch `runAllMods` only when live-server verification is explicitly needed,
   with the port and process ownership rules in `$tamework-runtime-safety`.
   Never stop a server that you did not launch in this session.
2. Confirm JSON paths and IDs against current registration and source.
3. If runtime or tick code changed, use `$tamework-runtime-safety` and run its
   current guard checks.
4. Report code edits, asset follow-up, and verification evidence. If base-game
   evidence was used, cite the rune-kb view and snapshot plus the FQCN, method,
   asset path, or UI path that drove the Tamework decision.

## Localize Player-Facing Text

Follow `AGENTS.md` -> **Player-Facing Localization** for all player-facing text (language keys, real translations in every locale in the same change, `es-ES` included).
