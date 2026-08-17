---
name: tamework-modding
description: Use when working on Alec's Tamework or downstream mods that depend on Tamework. Handles Tamework Java registrations, `Tw*Config` assets, custom NPC builders, item interactions, ECS components, and role templates while verifying IDs and behavior against current source plus an exact-profile HytaleNpcAssetTools session.
---

# Tamework Modding

Use this skill to keep Tamework edits coherent across Java registrations, JSON assets, and NPC role wiring.

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
   - `Behavior change`: modify existing Tamework Java or JSON behavior.
   - `New type`: add a new action/sensor/filter/component/asset family.

## Load Only Needed References

- Read `references/type-discovery.md` for the current-source discovery
  procedure.
- Read `references/asset-paths-and-resolution.md` for locator hints, then verify
  paths, resolution, and reload behavior in the current source commit.
- When creating or editing any `Tw*Config` Java/codec/default JSON, load and follow:
  `C:\Users\22ale\.codex\skills\hytale-asset-inheritance-contract\SKILL.md`
  (treat this as required for config inheritance/fallback/tooltip/test behavior).
- When modifying optimized interactions, also read:
  `C:\Users\22ale\AppData\Roaming\Hytale\Modding\alecstamework\docs\Interactions.md`
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
   - For new `Tw*Config` families, update the config class, `Tamework.java`
     asset registration/event hooks, and docs.
   - For new item interaction types, update the interaction class and
     `Interaction.CODEC.register(...)` in `Tamework.java`.
3. Preserve stable IDs.
   - Treat action/sensor/filter `BUILDER_ID`, interaction type names, component
     names, and asset paths as API.
   - Avoid renaming existing IDs unless explicitly requested as a breaking
     change.
4. Keep config behavior consistent.
   - For all `Tw*Config` work, apply the
     `hytale-asset-inheritance-contract` skill rules; do not implement ad-hoc
     inheritance behavior.
   - When adding new config fields/sections, update parent fallback, codec
     tooltip docs, and tests per that contract.
5. For asset changes, build a read-only candidate and run `author validate
   --scope affected`. Generate verification for behavior-sensitive builders,
   role changes, ownership, lifecycle, target-loss, and downstream consumers.

## Apply Tamework-Specific Rules

- When current registration/profile evidence exposes `TameworkInteract` and
  `TwInteractionConfig`, prefer that optimized interaction flow unless the user
  asks for full vanilla instruction chains.
- When a change depends on vanilla Hytale semantics, APIs, gamedata, client UI, or javadocs, use `hytale-workshop-mcp` for the base-game evidence before designing the Tamework-side edit.
- For runtime reports, compare source repo and `Modding\run\mods` before assuming the game loaded the edited files. Use `UserData\Mods` only when diagnosing a legacy/manual install.
- Verify `/tw reloadconfig` behavior in the current command and reload-service
  source. At the last review it reloaded only the spawner, naming-item, and
  command-item feature families; do not assume that list remains current.
- Verify whether other `Tw*Config` families use normal asset loaded/removed
  events before prescribing a reload workflow.
- Thread-affinity rule for player access:
  - Do not use `PlayerRef.getComponent(Player)` inside `TickingSystem.tick`, world tick systems, or async/delayed executors.
  - In tick/system code, resolve players from the active world/store (`world.getEntityRef(uuid)` + `store.getComponent(ref, Player.getComponentType())`).
  - Do not pass `Player` component objects into async/deferred callbacks. Pass `UUID` and resolve on world/tick thread.
  - Avoid `Universe.getPlayers()` + player component access in runtime tick paths.
- Do not add fallback helper methods that iterate `Universe.getPlayers()` for runtime remap/mutation logic.
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
5. Run a thread-safety grep pass for player access:
`rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java`
6. If base-game evidence was used, cite the Workshop corpus/version plus FQCN, method, asset path, or UI path that drove the Tamework decision.
