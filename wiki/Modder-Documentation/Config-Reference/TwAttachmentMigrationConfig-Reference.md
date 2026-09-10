---
title: "TwAttachmentMigrationConfig Reference"
order: 19
published: true
draft: false
---
# TwAttachmentMigrationConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

`TwAttachmentMigrationConfig` backfills missing model attachment slots from already-stored attachment selections. Use it when a mod splits an existing appearance choice into a new random attachment set and existing NPCs need a deterministic upgrade path.

Tamework also repairs missing random attachment sets automatically when existing
NPCs load, including wild NPCs without a migration config. After preserving valid
saved selections and applying any explicit migration rules, it fills remaining
sets using the current model's normal `RandomAttachmentSets` weights. The choices
are saved and reused on later loads. A valid invisible option such as `None`
counts as a selection and is preserved.

This covers installing a texture or model mod mid-playthrough: existing animals
receive the newly split parts as their chunks load. It does not scan unloaded
chunks or reroll existing choices. Fresh spawns keep the game's normal selection
behavior; an asset hot reload requires an NPC reload before this repair runs.

## Path
`Server/Tamework/AttachmentMigrations/*.json`

## Resolution
- Role-scoped by `RoleIds`
- Higher `Priority` wins when multiple enabled configs match the same role
- The active config runs during attachment seed, replace, and sync paths

## Runtime Behavior
- Rules only add a target slot when that target slot is missing
- Existing target selections are preserved, so new randomized NPCs and inherited offspring values are not overwritten
- Source and target values must both be valid options on the current model
- Unsupported or unmapped values are skipped without failing the NPC load

## Inheritance
- Omitted top-level fields inherit from the parent
- Explicit arrays replace the parent value
- `Rules[].SourceToTarget` is an explicit map and replaces the parent map when a child authors it

## Top-Level Fields
- `Enabled`
- `Priority`
- `RoleIds`
- `Rules`

## `Rules`
Each rule maps one existing attachment slot to one new target slot.

- `SourceSlot`: existing attachment set to read, such as `Coat`
- `TargetSlot`: attachment set to fill when absent, such as `Eyes`
- `SourceToTarget`: map of source option id to target option id

## Example
```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": [
    "Cat_Pet",
    "Cat"
  ],
  "Rules": [
    {
      "SourceSlot": "Coat",
      "TargetSlot": "Eyes",
      "SourceToTarget": {
        "Black": "BrightOrange",
        "White": "Odd_Blue-GreenYellow"
      }
    }
  ]
}
```

## Related Pages
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference)
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
