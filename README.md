[![Cats](https://img.shields.io/curseforge/dt/1432112?label=Cats&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-cats)
[![Tamework](https://img.shields.io/curseforge/dt/1447962?label=Tamework&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-tamework)
[![Nametags](https://img.shields.io/curseforge/dt/1464844?label=Nametags&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-nametags)
[![Animal Husbandry](https://img.shields.io/curseforge/dt/1480275?label=Animal%20Husbandry&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-animal-husbandry)

[![Discord](https://img.shields.io/discord/1468261809739005996?style=for-the-badge&logo=discord&logoColor=white&label=Join%20Discord&color=rgb(88,101,242))](https://discord.gg/E8n8RgTTdq) [![GitHub Repo stars](https://img.shields.io/github/stars/Alechilles/AlecsCats?style=for-the-badge&logo=github&label=GitHub)](https://github.com/Alechilles/AlecsTamework)

# Alec's Tamework!
A modular taming framework for Hytale that focuses on fast setup and giving modders reusable systems for companion NPCs. Add ownership, taming, capture/spawn, command tools, and rich NPC interactions without huge custom instruction trees in every role.

## This is a Library!
This mod does not add anything to the game on it's own. If you are a player looking for taming/breeding/etc features, check out [Alec's Animal Husbandry](https://www.curseforge.com/hytale/mods/alecs-animal-husbandry), which adds all the features of Tamework to the vanilla Livestock and Predator mobs.

## Core Features
- **Interaction System** - Use `TwInteractionConfig` assets to create NPC interactions quickly and easily.
  - Full asset-editor GUI support unlike large vanilla instruction chains.
  - Requires far less JSON than the base interaction flow in most cases.
  - Interaction prompts update automatically from requirements.
  - Role parameters let one interaction config drive many NPC roles.
  - `TriggerNpcHook` + `TameworkHook` bridges optimized interactions into custom instruction logic.
- **Ownership and Tamed Components** - Enforce ownership and tamed status with simple actions/sensors.
  - Sensors for ownership and tamed status.
  - Global and per-role behavior controls for who can interact with or damage owned NPCs.
- **Capture/Spawn NPCs With Metadata** - Capture saves metadata and restores the same NPC on spawn.
  - Includes random attachments such as varying textures, models, etc.
  - Includes persisted progression state such as ownership/tamed, happiness, needs, breeding readiness/cooldowns, traits, life stage, and attachment selections.
  - Ownership can optionally be cleared on capture and re-set on spawn to enable player trading of captured NPCs.
  - Optional DynamicTooltipsLib bridge adds captured-spawner tooltip lines (`Name`, `Role`) with per-item `TooltipMode` (`Additive` or `Replace`).
- **Naming Items** - Name tamed NPCs with an in-game input UI using `TwNameItemConfig` + `TameworkNameNpc`.
  - Works with any custom item; not a fixed nametag item
  - Per-item rules for roles, ownership, allowed characters, rename rules, and more
- **Command Item System** - Build custom command tools with `TwCommandItemConfig` + `TameworkCommand`.
  - Link/unlink NPCs to each tool.
  - Left-click executes the selected command; right-click opens the radial command wheel.
  - Radial menu includes a linked-companions side panel with loaded/unloaded/dead status and per-NPC actions (`Recall`, `Set Home`, `Return Home`, `Unlink`, `Revive` when enabled and ready).
  - Linked panel rows update status/health/cooldowns in-place while open.
  - Unlink safety is configurable via `TwGlobalConfig.Command.LinkedPanelRequireUnlinkConfirm`.
  - Supports command steps like state changes, target assignment, move-to-ping, set/return-home, and hook triggers.
  - Includes off-screen command queueing + chunk preload retries for recall/return-home relocation with safer placement for recall/revive.
  - Supports strict lost-companion recovery (`LOST` state + stale-original suppression mapping) and nearby-only `Release`/`Cull` actions in linked panel flows.
  - Companion command behavior (recall/return-home/respawn distances and timing) can be scoped per role with `TwCompanionConfig`.
  - Companion travel relocation policy can be scoped per role with `TwCompanionConfig.Command.Travel` (`CrossWorldRecallEnabled`, `OnTransferFailure`, `FollowMasterOnWorldChange`, `FollowMasterOnWorldChangeStateFilter`).
  - Command relocation infrastructure tuning remains global in `TwGlobalConfig.Command` (`RelocationRetryIntervalMs`, `RelocationMaxWaitMs`, `RelocationMaxRetryAttempts`).
  - `/tw debugdespawn [on|off] [RoleName|all|clear]` supports role-filtered despawn diagnostics for tamed companions.
- **Happiness and Needs System** - Shared progression state for companion wellbeing and behavior pressure.
  - Role-priority config resolution for happiness (`TwHappinessConfig`) and needs (`TwNeedsConfig`).
  - Hunger/thirst decay, passive refill, resource-seek support, and needs-driven happiness penalties.
  - In-game balancing commands such as `/tw gethappiness`, `/tw sethappiness`, `/tw getneeds`, and `/tw setneeds`.
- **Breeding System** - Config-driven breeding flow with readiness, pairing, and offspring lifecycle handling.
  - Readiness/cooldown rules from `TwBreedingConfig`, including alarm/timer-backed gating.
  - Nearby partner matching, pair approach sequencing and offspring spawn flow.
  - Supports baby variants and life-stage progression integration.
- **Traits and Inheritance System** - Deterministic trait assignment and inheritance for long-term variation.
  - Deterministic trait rolls with duplicate/conflict controls and role-scoped trait pools (`TwTraitConfig`).
  - Inheritance/mutation flows with trait modifiers for health, speed, damage, harvest, and other gameplay effects.
  - In-game authoring/debug commands such as `/tw gettraits`, `/tw settraits`, `/tw addtrait`, and `/tw getlifestage`.
- **Coop Integration** - Tamework-managed coop runtime for configured coop ids.
  - Configure per-coop behavior through `TwCoopConfig` assets keyed by `CoopId`.
  - Managed coop capture/release uses a resident ledger to preserve state continuity across coop cycles.
- **Examples and Documentation** - Plenty of examples and thorough documentation to help you integrate Tamework.
  - [Check out the wiki here](https://github.com/Alechilles/AlecsTamework/wiki)

## Roadmap
- **NPC XP/leveling system**
  - Gain XP passively when with owner or when doing certain configurable actions
- **Talent trees**
  - Create your own talent trees for your NPCs
  - Allow unlocking new behaviors, stat increases, etc.
  - Will include a talent tree UI

## Quick Start (2.5.x)
1. Add the dependency in your `manifest.json`:

```json
"Dependencies": {
"Alechilles:Alec's Tamework!": "2.5.0"
},
"IncludesAssetPack": true
```

Optional for custom spawner tooltip lines:

```json
"OptionalDependencies": {
  "org.herolias:DynamicTooltipsLib": "1.5.x"
}
```

**Asset pack note:** Tamework ships as a jar with embedded `Common/` + `Server/` assets. At load time, Tamework also enforces early asset-pack ordering and removes legacy standalone `Alec's Tamework! (Assets)` packs/archives when detected.

2. Choose your interaction path:
   Tamework's optimized system (`TameworkInteract` + `TwInteractionConfig`) or vanilla instruction flow (full control but a lot more work).
   This quick-start guide will use `TameworkInteract`, but you can find more information on the components for use with the [vanilla flow here](https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Vanilla).

3. Copy a template:
   `Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json` or `Template_Tamework_Example_Simple.json`

4. Copy a matching NPC role and tweak values:
   `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example.json` (or the simple variant)

5. Create a `TwInteractionConfig` under:
   `<ModRoot>/Server/Tamework/Interactions/`

6. Wire the interaction instruction in your template:

```json
"InteractionInstruction": {
  "Actions": [
    {
      "Type": "LockOnInteractionTarget",
      "TargetSlot": { "Compute": "MasterTargetSlot" }
    },
    { "Type": "TameworkInteract" }
  ]
}
```

Optional prompt updater (see the example template for full usage):

```json
{
  "Continue": true,
  "Sensor": { "Type": "Any" },
  "Actions": [ { "Type": "TameworkInteractPrompt" } ]
}
```

7. Spawner items (optional):
   Create a `TwSpawnerConfig` asset under `<ModRoot>/Server/Tamework/Items/Spawners/` and wire your item with `TameworkSpawn`.

8. Naming items (optional):
   Create a `TwNameItemConfig` asset under `<ModRoot>/Server/Tamework/Items/Naming/` and add `TameworkNameNpc` to your item’s `Interactions`.

9. Command items (optional):
   Create a `TwCommandItemConfig` asset under `<ModRoot>/Server/Tamework/Items/Commands/` and add `TameworkCommand` interactions to your tool item:

```json
"Interactions": {
  "Primary": {
    "Interactions": [ { "Type": "TameworkCommand" } ]
  },
  "Secondary": {
    "Interactions": [ { "Type": "TameworkCommand", "CommandId": "OpenSelectionMenu" } ]
  }
}
```

10. Coop runtime config (optional):
   Create a `TwCoopConfig` under `<ModRoot>/Server/Tamework/Items/Coops/` keyed to your coop id (`CoopId`, for example `Coop_Chicken`) to configure Tamework-managed coop capture/release behavior.

11. After editing spawner, naming, or command item configs, use `/tw reloadconfig`.

12. Add translations in `Server/Languages/en-US/server.lang`.

## Configuration Overview
- **TwGlobalConfig**: sectioned global defaults and runtime tuning (`General`, `InteractionDefaults`, `Command` infrastructure).
  Location: `<ModRoot>/Server/Tamework/Global/*.json`
- **TwCompanionConfig**: role-scoped companion behavior policy (`OwnershipProtection` + per-role `Command` behavior tuning) with priority + parent fallback support.
  - `Command.DeadRespawnCooldownMins` is supported as a human-friendly alias and overrides `Command.DeadRespawnCooldownMs` when both are set.
  Location: `<ModRoot>/Server/Tamework/Companion/*.json`
- **TwSpawnerConfig**: spawner capture/spawn behavior.
  Location: `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- **TwNameItemConfig**: naming item behavior.
  Location: `<ModRoot>/Server/Tamework/Items/Naming/*.json`
- **TwCommandItemConfig**: command-tool behavior and command list.
  Location: `<ModRoot>/Server/Tamework/Items/Commands/*.json`
- **TwBreedingConfig**: breeding eligibility/timing/inheritance behavior.
  Location: `<ModRoot>/Server/Tamework/Breeding/*.json`
- **TwHappinessConfig**: shared happiness progression and equilibrium modifiers.
  Location: `<ModRoot>/Server/Tamework/Happiness/*.json`
- **TwNeedsConfig**: hunger/thirst progression, passive refill, and needs-resource seek behavior.
  Location: `<ModRoot>/Server/Tamework/Needs/*.json`
- **TwTraitConfig**: role-scoped trait pools and inheritance behavior.
  Location: `<ModRoot>/Server/Tamework/Traits/*.json`
  Notes: each trait definition can now optionally set `IconPath` for linked-panel icon rendering; when omitted, the UI falls back to first-letter glyphs.
- **TwCoopConfig**: coop-id scoped managed coop runtime behavior.
  Location: `<ModRoot>/Server/Tamework/Items/Coops/*.json`
- After editing spawner, naming, or command item configs, use `/tw reloadconfig`.

## Contributor Architecture Snapshot
- Runtime systems use orchestrators plus focused services (selection, validation, persistence, UI view-models, relocation, feedback) instead of single monolithic handlers.
- Main orchestrators:
  - `SpawnerFeatureHandler` (with `Spawner*Service` collaborators)
  - `NamingFeatureHandler` (with `Naming*Service` collaborators)
  - `CommandItemFeatureHandler` (with `Command*Service` collaborators)
- Detailed contributor map: `docs/Architecture.md`

## Documentation (Wiki)
- Home: https://github.com/Alechilles/AlecsTamework/wiki
- Quick‑Start: https://github.com/Alechilles/AlecsTamework/wiki/Quick-Start
- Interactions (Optimized): https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Optimized
- Interactions (Vanilla): https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Vanilla
- Items: https://github.com/Alechilles/AlecsTamework/wiki/Items
- Spawner Config (Assets): https://github.com/Alechilles/AlecsTamework/wiki/Item-Config
- Naming Items: https://github.com/Alechilles/AlecsTamework/wiki/Naming-Items
- Command Items: https://github.com/Alechilles/AlecsTamework/wiki/Command-Items
- Actions and Sensors: https://github.com/Alechilles/AlecsTamework/wiki/Actions-and-Sensors
- Hooks and Bridges: https://github.com/Alechilles/AlecsTamework/wiki/Hooks-and-Bridges
- Templates: https://github.com/Alechilles/AlecsTamework/wiki/Templates
- Components: https://github.com/Alechilles/AlecsTamework/wiki/Components
- Troubleshooting: https://github.com/Alechilles/AlecsTamework/wiki/Troubleshooting

## Issue Reporting
If you run into a bug or behavior issue, please submit a report:
https://github.com/Alechilles/AlecsTamework/issues
