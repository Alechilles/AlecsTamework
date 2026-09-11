---
title: "TwDynamicIconConfig Reference"
order: 6
published: true
draft: false
---
# TwDynamicIconConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference)

`TwDynamicIconConfig` maps NPC roles and attachment selections to companion icon
PNGs. Filled capture items, normal command panels, and bonded roster panels use
the same assets. A roster companion needs no spawner item or spawner config.

## Location and example

Store assets in `Server/Tamework/DynamicIcons/`. Each file defines one shared
appearance rule set for its listed roles:

```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": ["Sheep", "Tamed_Sheep"],
  "IconDefault": "Icons/ItemsGenerated/MyMod/Sheep/base.png",
  "IconOverrides": [
    {
      "Icon": "Icons/ItemsGenerated/MyMod/Sheep/black.png",
      "Attachments": {"Coat": "Black"}
    }
  ]
}
```

Icon paths are relative to `Common/`. Use actual attachment keys and values from
the model. The example paths and `Coat` predicate above are illustrative.

## Selection

- `Enabled` defaults to `true`; disabled assets do not participate.
- `Priority` defaults to `0`. The highest priority matching asset wins; ties
  use the case-insensitive lowest asset ID.
- `RoleIds` lists applicable roles. Matching ignores surrounding whitespace and
  letter case. An empty list matches nothing.
- The selected asset's `IconOverrides` are checked in authored order. The first
  entry with a nonblank icon whose nonempty `Attachments` map is a subset of the
  companion's attachments wins. Attachment keys and values are case-sensitive.
- `IconDefault` is used when no override matches, including when attachment data
  is missing. It is optional; no matching icon returns no companion icon.

Selection chooses one complete asset per role. It does not merge rules from
lower-priority assets. Combine skin-pack variants and the base default in one
asset, placing more specific predicates first. Alec's Cats uses priority `100`
so its coat variants take precedence over Animal Husbandry's generic cat asset
at priority `0`.

## Inheritance and reload

Omitted fields inherit from `Parent`. Explicit scalar values replace parent
values. Explicit `RoleIds` and `IconOverrides` arrays replace the whole parent
array, including an explicit empty array. Attachment maps inside override
entries are part of that replacement array.

The config editor exposes the **Dynamic Icons** family. Asset load, replacement,
and removal invalidate role lookup. Icon-only display items are registered from
enabled dynamic icon assets as assets load. Previously created display items
remain valid until shutdown so already-open cards can still reference them;
subsequent portrait resolution uses current configs. Config-change events use
`TameworkConfigFamily.DYNAMIC_ICONS`.

## Migrating spawner maps

Move each old `IconOverrideGroups` entry into its own asset: `Roles` becomes
`RoleIds`, `Overrides` becomes `IconOverrides`, and the group's `IconDefault`
stays with those roles. For former exact-role maps, list that role in `RoleIds`.
If a role had both exact and group rules, put exact rules first in its new array.
Global attachment rules must be scoped to the intended roles in the new assets.
Keep generic item imagery in the spawner's own `IconDefault`.

Remove the old inline maps from `TwSpawnerConfig`. There is no compatibility
fallback; update Tamework, dependent packs, and generated configs together.
Existing texture files do not need to move or be regenerated.

[Icon generation tooling](/mod/alecs-tamework/spawner-icon-generation) produces
these shared assets and render jobs.
