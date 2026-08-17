# Tamework Agent Map

Use this map before broad repo searches. It routes common Tamework tasks to the files, docs, tests, and external checks that usually matter first.

## First Checks

- Confirm the active source repo is `C:\Users\22ale\AppData\Roaming\Hytale\Modding\alecstamework`.
- Treat `C:\Users\22ale\AppData\Roaming\Hytale\UserData\Mods\alecstamework` as a runtime/synced copy unless the user explicitly asks to edit it.
- Check `git status --short` before edits and keep unrelated dirty files isolated.
- Prefer fixed-string `rg` searches for asset IDs, interaction type names, and class names before using broad regexes.
- If base-game behavior is unclear, search Hytale source/docs through hytale-rag or HytaleWorkshopMCP before designing Java changes.
- Repository-owned Codex skills live under `codex/skills`. Run
  `bash scripts/tools/link-codex-skills.sh` after adding or renaming one.

## Task Routing

| If the task mentions | Start with | Then check |
| --- | --- | --- |
| Patchwork, asset patches, `Target`, `Targets`, `When`, merge/insert operations | `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\docs`, external wiki `Asset-Patches.md` | `src/main/java/com/alechilles/alecstamework/integration/patchwork`, `TameworkPatchResourceLayoutTest`, and the Patchwork runtime tests |
| `Tw*Config` schema, codec, inheritance, override, editor, resolver, or reload behavior | `$tamework-config-authoring`, then `docs/Config-Discovery.md` | Relevant class under `config/assets`, registration/load hooks in `Tamework.java`, consumers, wiki config reference |
| `TwInteractionConfig`, prompts, optimized interactions, command actions | `$tamework-interaction-configurator`, then `docs/Interactions.md` | `src/main/java/com/alechilles/alecstamework/config/assets/TwInteractionConfig*.java`, `src/main/java/com/alechilles/alecstamework/interactions` |
| Command items, linked panels, command radial behavior | `$tamework-command-runtime`, then `docs/Command-Items.md` | `src/main/java/com/alechilles/alecstamework/items`, `src/main/java/com/alechilles/alecstamework/ui` |
| Spawners, naming items, icon generation | `docs/Spawner-Config.md`, `docs/Naming-Items.md` | `scripts/tools/generate_spawner_icon_overrides.py`, `src/main/java/com/alechilles/alecstamework/config/assets/TwSpawnerConfig.java` |
| Scarecrow placement or spawn suppression | `docs/Scarecrow.md` | `src/main/java/com/alechilles/alecstamework/items/scarecrow`, `src/main/resources/Server/NPC/Spawn/Suppression` |
| Avatar flight, transformed-player flight, fake rider visuals | `$tamework-avatar-flight`, then `docs/Avatar-Flight.md` | `src/main/java/com/alechilles/alecstamework/avatarflight`, `src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java`, `scripts/tools/avatarflight_namespace_assets.py` |
| Needs, happiness, breeding, traits, dynamic attachments, progression | `$tamework-companion-progression`, then `wiki/Modder-Documentation/Config-Reference` | `src/main/java/com/alechilles/alecstamework/npc/progression`, `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments`, relevant `Tw*Config` classes |
| NPC actions, sensors, filters, motions, builder registration | `docs/Actions-Sensors-Components.md` | `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`, `npc/actions`, `npc/sensors`, `npc/filters`, `npc/movement` |
| Public API interfaces, capabilities, results, events, versions, self-tests | `$tamework-api-evolution`, then `wiki/Modder-Documentation/Public-API` | `src/main/java/com/alechilles/alecstamework/api`, `api/internal`, `selftest`, API contract tests, downstream mods |
| Runtime systems, tick paths, async work, ECS writes | `$tamework-runtime-safety`, then `docs/agents/guardrails.md` | `src/test/java/com/alechilles/alecstamework/architecture`, thread-safety grep from `AGENTS.md` |
| Optional integrations or external mod bridges | `docs/Hooks-and-Bridges.md` | `src/main/java/com/alechilles/alecstamework/integration`, specific integration skill if present |
| Replacement persistence kernel, import, operations, recovery, projections | `$tamework-persistence`, then `docs/decisions/0001-persistence-replacement-boundaries.md` and `docs/decisions/0007-persistence-core-implementation.md` | `src/main/java/com/alechilles/alecstamework/companion/{capture,coop,dormant,extension,identity,lifecycle,profile,restoration,snapshot}`, `src/main/java/com/alechilles/alecstamework/persistence/{kernel,migration,operation,projection,runtime,adapter/sqlite,facade}`, `src/test/java/com/alechilles/alecstamework/persistence/{adapter/sqlite,architecture,migration}` |
| Bonded companions, bonded leases, bonded capture/revive, disposable projections, `bonded-companions.sqlite` | `$tamework-persistence`, then `docs/Required-Persistence-Feature-Inventory.md` | `src/main/java/com/alechilles/alecstamework/{api,companion/bonded,persistence/bonded}`, `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanion*.java`, focused bonded tests |
| Released persistence behavior, import/refusal, saved state | `wiki/Developer-Documentation/Data-and-Persistence/Persistence-Sqlite-and-Data-Paths.md` | `src/main/java/com/alechilles/alecstamework/persistence/{migration,runtime,kernel,adapter/sqlite}`, `src/main/java/com/alechilles/alecstamework/items/persistence` |
| Build, release, packaged jars/zips | `docs/Build-and-Packaging.md` | `scripts/release`, `.release`, GitHub workflow logs when publishing |
| Player-facing docs or release notes | `wiki/Player-Guides`, `CHANGELOG.md` | `docs/agents/generated-index.md` for current doc map |

## Source vs Runtime Checks

Use `docs/agents/runtime-vs-source-checklist.md` when a report says "the game still does X" or when a packaged artifact/runtime copy may be stale. Do not assume source edits are loaded until the runtime copy, jar, save override, or generated patch pack has been checked.

## Agent Indexes

- `docs/agents/generated-index.md` is generated by `scripts/tools/build-agent-index.ps1`.
- `docs/agents/lessons-index.md` points to durable external lesson notes under `C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs\Lessons Learned`.
- Rebuild the generated index after meaningful package, docs, test, or script layout changes.

