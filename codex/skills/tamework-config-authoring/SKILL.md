---
name: tamework-config-authoring
description: Use when creating or changing a Tamework `Tw*Config` family, codec field, shipped config asset, parent fallback, override behavior, config-editor schema, cache, load/remove hook, reload path, resolver, or config documentation. Also use before treating a codec-only change as complete.
---

# Tamework Config Authoring

Treat each config family as a runtime contract, not as one Java codec.

## Establish the Contract

1. Read `references/config-change-matrix.md`.
2. Find the current config class and all consumers. Do not infer a field's
   owning section from its requested name.
3. Inspect registration and load/remove hooks in `Tamework.java`.
4. Inspect the family resolver, cache, override support, and config-editor
   adapter or policy.
5. Read `docs/Config-Discovery.md` and the family reference under `wiki/`.
6. Load `$hytale-asset-inheritance-contract` for every `Tw*Config` codec,
   parent, fallback, tooltip, or default-value change.

## Make the Change

- Add a field only when a runtime consumer gives it observable meaning.
- Keep codec default, Java default, validation, accessor, consumer behavior,
  inherited value, shipped examples, and documentation consistent.
- Preserve existing asset IDs, paths, resolution keys, and defaults unless the
  user approves a breaking change.
- Verify whether the config editor discovers the codec automatically before
  adding UI code.
- Verify current load/remove and cache invalidation behavior before changing
  `/tw reloadconfig`. Do not assume that command owns every config family.
- If a class is over the repository size limit, keep it orchestration-only and
  extract the new domain concern.

## Route Related Work

- Use `$tamework-interaction-configurator` for `TwInteractionConfig` prompt,
  action, sensor, state, and cooldown lifecycle work.
- Use `$tamework-persistence` when config values affect saved state,
  restoration, migration, or override persistence.
- Use `$tamework-test-authoring` only after the test-value gate identifies an
  observable production regression.
- Use `hytale-workshop-mcp` when the contract depends on base-game semantics
  that current Tamework source does not establish.

## Verify

1. Run a focused behavior test for parsing, resolution, inheritance, or the
   consuming service. Do not add source-text or asset-presence tests.
2. Run `bash ../gradlew -p .. :alecstamework:test` for Java changes.
3. Run `bash ../gradlew -p .. stageAllModAssets` when staged asset validation
   is needed. This command does not launch the server.
4. Report the changed config family, resolution key, inheritance behavior,
   reload behavior, affected consumers, documentation, and any evidence gaps.
