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
- Saved state, SQLite, snapshots, operations, recovery, or migration:
  `$tamework-persistence`.

Load more than one focused skill when a change crosses boundaries.

## Load Registration References

- Read `references/type-discovery.md` for the current-source discovery
  procedure.
- Read `references/asset-paths-and-resolution.md` only for path discovery. Use
  `$tamework-config-authoring` for any config-family change.
- When modifying builder registrations, also read:
  `C:\Users\22ale\AppData\Roaming\Hytale\Modding\alecstamework\src\main\java\com\alechilles\alecstamework\npc\TameworkNpcBuilderRegistrar.java`

## Execute Change Pattern

1. For NPC JSON work, also use `$hytale-asset-tools`:
   - lock the intended release profile and plugin set;
   - inspect declared/effective assets, references, findings, and actionable
     advisories;
   - use `author options` rather than remembered builder IDs;
   - retain profile, knowledge, and snapshot identity.
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
4. For asset changes, build a read-only candidate and run `author validate
   --scope affected`. Generate verification for behavior-sensitive builders,
   role changes, ownership, lifecycle, target-loss, and downstream consumers.

## Apply Tamework-Specific Rules

- When a change depends on vanilla Hytale semantics, APIs, gamedata, client UI, or javadocs, use `hytale-workshop-mcp` for the base-game evidence before designing the Tamework-side edit.
- For runtime reports, compare source repo and `Modding\run\mods` before assuming the game loaded the edited files. Use `UserData\Mods` only when diagnosing a legacy/manual install.
- Treat current Java registration plus the exact project profile as authority.
  A skill reference, old asset, shipped example, runtime copy, or remembered ID
  is discovery evidence only.
- Keep knowledge heuristics review-only. Do not auto-remove fallbacks, flatten
  inherited defaults, or reorder catch-all behavior without explicit review.

## Validate Before Finalizing

1. Run Java tests when code changes are made from the shared workspace:
`bash ../gradlew -p .. :alecstamework:test`
   Use `bash ../gradlew -p .. stageAllModAssets` to stage configured Java mods
   and linked asset packs into `Modding\run\mods`; do not use `runAllMods` for
   staging, because it launches a Hytale server.
   Launch `runAllMods` only when live-server verification is explicitly needed.
   Before launching, verify that no existing test server owns the target port,
   record the launched process tree, and terminate that exact tree immediately
   after collecting the required evidence. Never stop a server that Codex did
   not launch.
2. Confirm JSON paths and IDs against current registration/source, then run
   exact-profile affected-scope candidate validation.
3. Execute the generated verification plan to the available static/live level;
   report unsupported claims as gaps.
4. Report code edits, asset follow-up, project/knowledge/snapshot identity,
   candidate outcome, and verification evidence.
5. If runtime or tick code changed, use `$tamework-runtime-safety` and run its
   current guard checks.
6. If base-game evidence was used, cite the Workshop corpus/version plus FQCN,
   method, asset path, or UI path that drove the Tamework decision.
