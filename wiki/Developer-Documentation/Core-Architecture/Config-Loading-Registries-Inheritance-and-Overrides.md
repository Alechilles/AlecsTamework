---
title: "Config Loading, Registries, Inheritance, and Overrides"
order: 5
published: true
draft: false
---
# Config Loading, Registries, Inheritance, and Overrides

Parent: [Core Architecture](/mod/alecs-tamework/core-architecture-index) | [Developer Documentation](/mod/alecs-tamework/developer-documentation-index)

## Asset registration
`Tamework.java` registers each config asset family during setup and tracks registration state with per-family booleans such as `globalAssetsRegistered`, `commandAssetsRegistered`, and `debugAssetsRegistered`.

## Item feature registries
- `ItemFeatureRegistry`
- `NameItemRegistry`
- `CommandItemRegistry`

These are the registries that `/tw reloadconfig` refreshes directly.

## Inheritance model
- Asset families implement parent fallback through `TwParentFallbackAsset`
- `TwAssetInheritanceFallback` repairs inherited views across the asset map
- Sectioned configs inherit missing nested fields instead of forcing full subtree replacement

## Override runtime
`TwConfigOverrideManager` discovers local override JSON, merges it with source JSON, stages a fully materialized pack, and loads that merged view back into the asset store. It also owns snapshot and staging directories under the universe-local override root.

## Editor schema support
- `TwConfigSchemaAdapter` derives editable field descriptors from codec schema metadata
- `TwConfigEditorFieldPolicy` controls field presentation
- `TameworkConfigEditorPage` is the UI entry point for in-game editing flows

## Reload boundaries
- Item configs: explicit registry reload
- Other config families: asset load and remove events
- Override reload suppresses selected asset events to avoid recursive or inconsistent refresh behavior

## Related Pages
- [Bootstrap, Builder Registration, and Extension Points](/mod/alecs-tamework/bootstrap-builder-registration-and-extension-points)
- [Optimized Interaction Pipeline Internals](/mod/alecs-tamework/optimized-interaction-pipeline-internals)


