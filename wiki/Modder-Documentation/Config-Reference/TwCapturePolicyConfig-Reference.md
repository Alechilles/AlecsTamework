---
title: "TwCapturePolicyConfig Reference"
order: 21
published: true
draft: false
---
# TwCapturePolicyConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

> **Tamework 3.0.0 / experimental API 0.9**
> Assets can be indexed independently of API availability. A Java integration
> must still require the `CAPTURE_POLICY` capability before relying on the
> authoritative probabilistic capture runtime.

## What it controls

`TwCapturePolicyConfig` assigns role-side capture difficulty and optional
side-effect-free custom requirements. Capture-item power and base chance stay
in `TwSpawnerConfig.Capture`, allowing one role policy to apply consistently to
several capture items.

## Asset location and resolution

- Location: `<ModRoot>/Server/Tamework/CapturePolicies/*.json`
- Scope: exact source NPC role IDs
- Winner: higher `Priority`, then case-insensitive asset ID, then case-sensitive
  asset ID
- Reload: normal asset loaded/removed events rebuild the compiled index

If a rebuild encounters an invalid asset, Tamework retains the last valid
compiled index and logs the asset-specific error.

## Fields

| Field | Default | Meaning |
| --- | --- | --- |
| `Enabled` | `true` | Disabled assets are excluded. |
| `Priority` | `0` | Winner precedence for overlapping role IDs. |
| `RoleIds` | `[]` | Exact source roles. An enabled empty array is invalid. |
| `Difficulty` | defaults below | Role-side chance policy. |
| `Requirements` | `[]` | Namespaced custom capture requirements. |

`Difficulty` fields:

| Field | Default | Meaning |
| --- | --- | --- |
| `MinimumPower` | `0` | Lower item power is ineligible and never rolls. |
| `Resistance` | `0.0` | Chance subtracted before multiplication. |
| `ChanceMultiplier` | `1.0` | Non-negative multiplier. |
| `MissingHealthBonus` | `0.0` | Maximum bonus multiplied by missing-health fraction. |
| `GuaranteedAtPower` | omitted | At or above this non-negative power, eligible capture is guaranteed. |

`Requirements` entries support:

- `Id`: required namespaced handler ID.
- `Param`: optional short parameter.
- `Values`: immutable string array.
- `JsonPayload`: optional valid JSON text, limited to 8,192 characters.

Handlers are registered through the capture-policy extension API. They must be
side-effect-free because Tamework invokes them during eligibility and final
revalidation.

## Inheritance

- Omitted top-level values inherit from the parent.
- An explicit `Difficulty` object overrides authored nested fields and inherits
  its missing nested fields.
- Explicit `RoleIds` and `Requirements` arrays replace the parent arrays; they
  never append or merge.

## Chance formula

For an eligible `TwSpawnerConfig` using `ChanceMode: Probability`:

```text
powerDelta = max(0, Power - MinimumPower)
missingHealthFraction = clamp(1 - currentHealth / maximumHealth, 0, 1)
rawChance = (BaseChance
             + powerDelta * ChancePerPower
             + MissingHealthBonus * missingHealthFraction
             - Resistance)
            * ChanceMultiplier
effectiveChance = clamp(rawChance, MinimumChance, MaximumChance)
```

`ChanceMode: Guaranteed` bypasses this family, including its requirements.

## Example

```json
{
  "Enabled": true,
  "Priority": 100,
  "RoleIds": [
    "Hydra"
  ],
  "Difficulty": {
    "MinimumPower": 3,
    "Resistance": 0.2,
    "ChanceMultiplier": 0.7,
    "MissingHealthBonus": 0.25,
    "GuaranteedAtPower": 5
  },
  "Requirements": [
    {
      "Id": "hydragon:special_encounter_capture_ready",
      "Param": "grounded_phase"
    }
  ]
}
```

## Validation rules

- Role IDs must be nonblank and unique inside one asset.
- Numeric values must be finite and non-negative.
- `GuaranteedAtPower` cannot be negative.
- Requirement IDs must be namespaced.
- Invalid assets are rejected instead of silently clamped.

## Related pages

- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [Capture Policy API Reference](/mod/alecs-tamework/capture-policy-api-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)
