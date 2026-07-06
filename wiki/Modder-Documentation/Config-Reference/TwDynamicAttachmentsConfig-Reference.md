---
title: "TwDynamicAttachmentsConfig Reference"
order: 13
published: true
draft: false
---
# TwDynamicAttachmentsConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

`TwDynamicAttachmentsConfig` lets a pack change an NPC's stored model attachment selections from config when runtime conditions match. It is intended for conditional appearance details such as named animals, low-needs states, tamed/untamed variants, traits, gender, life stage, and command states.

Assets live under:

```text
Server/Tamework/DynamicAttachments/*.json
```

## Resolution
Configs are role-scoped with `RoleIds`.

Tamework builds a role index from enabled configs, then evaluates rules in this order:

1. Higher config `Priority`
2. Higher rule `Priority`
3. Config id tie-break
4. Rule declaration order

For each attachment slot, the first matching rule wins. Later matching rules can still set different slots.

## Performance Model
The runtime is designed to be negligible on busy servers:

- NPCs whose role has no dynamic attachment rules are skipped before snapshots are read.
- Rules are pre-indexed by normalized role id instead of scanning all configs per NPC.
- Evaluation is staggered in periodic sweeps rather than running every tick.
- NPC state is fingerprinted, so unchanged snapshots do not rewrite attachment components.
- Attachment writes only happen when the resolved selections actually change.
- Temporary overlays remember only the affected slots, so reverting does not require rebuilding the whole NPC appearance state.

Avoid very large rule lists for one role when a smaller set of higher-level conditions would do. Prefer one config per related role group, with sparse priorities.

## Top-Level Fields
| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `Enabled` | boolean | `true` | Disabled configs are ignored. |
| `Priority` | integer | `0` | Higher priority configs evaluate first. |
| `RoleIds` | string array | `[]` | NPC role ids this config applies to. |
| `Rules` | rule array | `[]` | Ordered dynamic attachment rules. |

Parent fallback is supported. Explicit arrays replace the parent value.

## Rule Fields
| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `Id` | string | unset | Stable rule id used for diagnostics and reversible overlay keys. |
| `Priority` | integer | `0` | Higher priority rules evaluate first inside the config. |
| `Persistence` | string | `Permanent` | `Permanent` or `WhileMatching`. |
| `Conditions` | condition array | `[]` | All conditions must match. |
| `Attachments` | map | `{}` | Attachment slot name to attachment option id. |

## Persistence Modes
`Permanent` writes the selected attachment into `TameworkAttachmentsComponent`. It remains after the condition stops matching, including across reloads, unless another system or rule changes that slot later.

`WhileMatching` applies a reversible overlay. Tamework stores the previous slot value, applies the rule value while all conditions match, and restores the previous value when the rule stops matching. If there was no previous value, the slot is removed again.

For example, a `NeedBelow` rule at `25` hunger with `WhileMatching` will remove or revert that attachment after hunger rises back to `25` or higher. The same rule with `Permanent` will not revert automatically.

## Condition Types
Condition type matching ignores case, spaces, underscores, and hyphens.

| Type | Required Fields | Meaning |
| --- | --- | --- |
| `DisplayNameEquals` | `Value` | Matches the NPC's custom display name. |
| `OwnerPresent` | `Expected` | Matches whether an owner component is present. |
| `TamedState` | `Expected` | Matches the tamed component state. |
| `Gender` | `Value` | Matches configured progression gender. |
| `LifeStage` | `Value` | Matches configured life stage. |
| `TraitPresent` | `TraitId`, optional `Expected` | Matches whether the trait exists. |
| `TraitValue` | `TraitId`, `Number` | Matches an exact trait numeric value. |
| `HappinessAtLeast` | `Number` | Matches happiness at or above the threshold. |
| `HappinessBelow` | `Number` | Matches happiness below the threshold. |
| `NeedAtLeast` | `Need`, `Number` | Matches a named need at or above the threshold. |
| `NeedBelow` | `Need`, `Number` | Matches a named need below the threshold. |
| `CommandStateEquals` | `State`, `Value` | Matches a command-state value. |

String conditions default `IgnoreCase` to `true`. Boolean conditions default `Expected` to `true`.

## Example: Named Moose Blanket
This adds the Canada blanket to moose named `Flash` and keeps it permanently.

```json
{
  "Enabled": true,
  "Priority": 100,
  "RoleIds": ["Mob_Moose"],
  "Rules": [
    {
      "Id": "flash_canada_blanket",
      "Priority": 100,
      "Persistence": "Permanent",
      "Conditions": [
        {
          "Type": "DisplayNameEquals",
          "Value": "Flash"
        }
      ],
      "Attachments": {
        "Blanket": "Blanket_Canada"
      }
    }
  ]
}
```

## Example: Low Hunger Blanket
This applies a temporary blanket while hunger is below `25`, then restores the previous blanket when hunger recovers.

```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": ["Mob_Moose"],
  "Rules": [
    {
      "Id": "hungry_blanket",
      "Priority": 50,
      "Persistence": "WhileMatching",
      "Conditions": [
        {
          "Type": "NeedBelow",
          "Need": "Hunger",
          "Number": 25
        }
      ],
      "Attachments": {
        "Blanket": "Blanket_Red"
      }
    }
  ]
}
```

## Related Pages
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [TwAttachmentDisplayConfig Reference](/mod/alecs-tamework/twattachmentdisplayconfig-reference)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference)
