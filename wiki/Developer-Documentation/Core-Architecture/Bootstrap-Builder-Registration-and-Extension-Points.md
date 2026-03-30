---
title: "Bootstrap, Builder Registration, and Extension Points"
order: 3
published: true
draft: false
---
# Bootstrap, Builder Registration, and Extension Points

Parent: [Core Architecture Index](/mod/alecs-tamework/core-architecture-index) | [Developer Documentation Index](/mod/alecs-tamework/developer-documentation-index)

## Entrypoint bootstrap
`Tamework.java` owns setup. It initializes registries, the asset pack coordinator, override management, persistence, feature handlers, telemetry, item interactions, components, and the various gameplay systems.

## Item interaction registration
During setup, Tamework registers:
- `TameworkSpawn`
- `TameworkNameNpc`
- `TameworkCommand`
- `TameworkClearFeedTroughWater`

## Builder registration timing
`TameworkNpcBuilderRegistrar` does not assume `NPCPlugin` is ready during plugin setup. It checks immediately, then falls back to `PluginSetupEvent` if needed. That avoids hard failures when the NPC plugin is still coming online.

## Registered builder groups
- Action builders for interaction, capture, ownership, taming, harvest, needs consumption, and debug messaging
- Sensor builders for ownership, tame state, life stage, hook state, effects, and needs targeting
- Entity filters for attitude and recent attack memory

## Safe extension points
- New item features should follow the existing feature-handler plus service pattern
- New config families should integrate through `config/assets`, the relevant asset store, and the override snapshot machinery
- New UI-backed config editing behavior should go through `TwConfigSchemaAdapter` and `TwConfigEditorFieldPolicy`

## Related Pages
- [Config Loading, Registries, Inheritance, and Overrides](/mod/alecs-tamework/config-loading-registries-inheritance-and-overrides)
- [Command and Debug Internals](/mod/alecs-tamework/command-and-debug-internals)

