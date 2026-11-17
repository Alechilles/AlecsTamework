---
title: "TwAttachmentMigrationConfig Reference"
order: 19
published: true
draft: false
---
# TwAttachmentMigrationConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

`TwAttachmentMigrationConfig` backfills missing model attachment slots from already-stored attachment selections. Use it when a mod splits an existing appearance choice into a new random attachment set and existing NPCs need a deterministic upgrade path.

It can also keep two attachment slots matched permanently with `"Mode": "Linked"`, for example a sheep's wool color following its fluff color. See [Linked Slots](#linked-slots).

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
- Backfill rules (the default) only add a target slot when that target slot is missing
- Existing target selections are preserved by backfill rules, so new randomized NPCs and inherited offspring values are not overwritten
- Linked rules always set the target slot from the source slot; see [Linked Slots](#linked-slots)
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
- `Mode`: `Backfill` (default) or `Linked`. Matching ignores case.

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

## Linked Slots
A model's random attachment sets are rolled independently, so two slots that should match, such as `Fluff` and `Wool` on a sheep, often don't. A rule with `"Mode": "Linked"` makes the target slot follow the source slot every time Tamework resolves an NPC's attachments:

- **Fresh spawns:** roles with a linked rule are resolved and saved when they spawn, so a new animal never keeps a mismatched pair.
- **Offspring:** after slots are inherited from both parents, linked targets are re-derived from the inherited source.
- **Loads and reloads:** existing NPCs with a mismatched pair are corrected the next time they load.
- **Shearing and other harvests:** Tamework does not reapply saved attachments while the harvest cooldown runs. When the cooldown ends, the saved, matched target comes back.

Linked rules run in declaration order and read earlier results, so `Fluff` → `Wool` → `Tail` chains work.

The target is left unchanged when the source value has no entry in `SourceToTarget`, or when the mapped value is not an option on the current model. Map every source option you want linked.

A linked target always wins over other writers of that slot, such as dynamic attachment rules and breeding inheritance. Don't link a slot that something else should control.

List every role that uses the model in `RoleIds`, including baby and adolescent roles, so offspring are matched too.

### Example: Wool Follows Fluff
```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": ["Sheep", "Sheep_Lamb"],
  "Rules": [
    {
      "SourceSlot": "Fluff",
      "TargetSlot": "Wool",
      "Mode": "Linked",
      "SourceToTarget": {
        "White": "White",
        "Brown": "Brown",
        "Grey": "Grey",
        "Black": "Black"
      }
    }
  ]
}
```

## Related Pages
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference)
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
