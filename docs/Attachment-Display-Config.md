# Attachment Display Config (TwAttachmentDisplayConfig)

`TwAttachmentDisplayConfig` assets provide player-friendly labels for raw model attachment selections. They are presentation-only and do not change attachment persistence, inheritance, migration, capture, or spawn behavior.

Assets live under:

`Server/Tamework/AttachmentDisplays/*.json`

## Example

```json
{
  "Type": "TwAttachmentDisplayConfig",
  "Enabled": true,
  "Priority": 0,
  "Entries": [
    {
      "Id": "base-game-cattle",
      "AppliesTo": {
        "RoleIds": ["Cow", "Yak"],
        "ModelIds": ["Cow"],
        "RoleNamespaces": ["Hytale"],
        "ModelNamespaces": ["Hytale"]
      },
      "Sets": {
        "BaseColor": {
          "Label": "Coat",
          "Values": {
            "Black": "Black Coat",
            "White": "White Coat"
          }
        }
      }
    }
  ]
}
```

## Fields

- `Enabled` (optional, default `true`). Turns the config on or off.
- `Priority` (optional, default `0`). Higher priority wins when multiple entries can label the same attachment.
- `Entries` (optional). Array of display entries. One config file can cover many NPCs by adding multiple entries.

Each entry has:

- `Id` (optional). Stable entry ID used for deterministic tie-breaking.
- `AppliesTo` (optional). Filters by exact `RoleIds` / `ModelIds` or by `RoleNamespaces` / `ModelNamespaces`.
- `Sets`. Map of raw attachment set IDs to labels and value names.

Missing or empty `AppliesTo` makes an entry a global fallback. Unknown attachment values fall back to raw IDs so missing display mappings are visible in tooltips.

## Resolution Order

For each captured attachment, Tamework checks all enabled configs and entries:

1. exact model ID
2. exact role ID
3. model namespace
4. role namespace
5. global fallback

Ties use higher `Priority`, then deterministic config ID and entry ID ordering.

When DynamicTooltipsLib is installed, captured spawner items use these display names automatically.
