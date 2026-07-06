# Dynamic Attachments Config Design

## Goal

Add a dedicated Tamework config system that can change NPC attachment selections when configured conditions match. The first motivating case is Animal Husbandry setting a moose named `Flash` to use the `Blanket_Canada` attachment.

The system must stay config-first, avoid one-off Java hooks, and add negligible load to servers with many mobs.

## Non-Goals

- Do not replace base-game random attachment sets or Tamework's existing attachment persistence.
- Do not make all conditional attachments permanent. Rules choose a persistence mode explicitly or fall back to `Permanent`.
- Do not fold this behavior into `TwAttachmentMigrationConfig`; migration remains schema/backfill behavior.
- Do not fold this behavior into `TwCompanionConfig`; dynamic attachment policy gets its own config family.

## Config Family

Add `TwDynamicAttachmentsConfig` under:

`Server/Tamework/DynamicAttachments/*.json`

Resolution is role-scoped:

- `Enabled`: disables the asset when false.
- `Priority`: config-level priority. Higher wins.
- `RoleIds`: role IDs this config can affect. Explicit child arrays replace parent arrays.
- `Rules`: conditional attachment rules. Explicit child arrays replace parent arrays.

Parent fallback follows the normal Tamework asset inheritance contract:

- Omitted top-level values inherit from the parent.
- Explicit arrays replace parent arrays.
- If object sections are added later, explicit nested keys override and missing nested keys inherit.

Example:

```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": [
    "Moose"
  ],
  "Rules": [
    {
      "Id": "flash_canada_blanket",
      "Priority": 100,
      "Persistence": "Permanent",
      "Conditions": [
        {
          "Type": "DisplayNameEquals",
          "Value": "Flash",
          "IgnoreCase": true
        }
      ],
      "Attachments": {
        "Blanket": "Blanket_Canada"
      }
    }
  ]
}
```

Temporary example:

```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": [
    "Moose"
  ],
  "Rules": [
    {
      "Id": "hungry_blanket",
      "Priority": 100,
      "Persistence": "WhileMatching",
      "Conditions": [
        {
          "Type": "NeedBelow",
          "Need": "Hunger",
          "Value": 25
        }
      ],
      "Attachments": {
        "Blanket": "Blanket_Canada"
      }
    }
  ]
}
```

## Rule Semantics

Each rule is AND-based: every condition on the rule must match.

When multiple rules match:

- Winners are selected per attachment slot.
- Higher config priority wins first.
- Within the winning config priority tier, higher rule priority wins.
- Deterministic tie-breaks use normalized asset ID, then rule declaration order.

Persistence modes are explicit:

- `Permanent`: when a rule wins, its attachment selections are merged into the NPC's stored attachment state. The selected slots remain after the original condition stops matching.
- `WhileMatching`: when a rule wins, its attachment selections are applied as a reversible dynamic overlay. When the rule stops matching and no other `WhileMatching` rule wins that slot, the slot restores to the value it had before the temporary rule first applied.

A later matching rule can overwrite a slot only if it wins that slot by priority/tie-break. `WhileMatching` winners should take effect over the stored base value while active, but they must not permanently erase the original slot value.

For `WhileMatching`, restore means:

- If the slot had a value before the temporary rule first applied, restore that value.
- If the slot was absent before the temporary rule first applied, remove that slot when restoring.
- If the slot was externally changed after the dynamic rule applied, do not blindly overwrite it; clear the dynamic baseline only when the implementation can prove it is restoring the value it wrote.

## Condition Types

The first version supports:

- `DisplayNameEquals`: compares against persistent display name first, then runtime display name.
- `OwnerPresent`: checks whether the NPC has a Tamework owner.
- `TamedState`: checks tamed/untamed state.
- `Gender`: checks Tamework progression gender state.
- `LifeStage`: checks Tamework life-stage state.
- `TraitPresent`: checks whether a trait ID exists on the NPC.
- `TraitValue`: checks a trait ID's value with exact or range matching.
- `HappinessAtLeast`: checks happiness value.
- `HappinessBelow`: checks happiness value.
- `NeedAtLeast`: checks a named need value.
- `NeedBelow`: checks a named need value.
- `CommandStateEquals`: checks Tamework command state when command links/state are present.

Condition parsing should be strict enough to warn on malformed rules, but runtime evaluation should skip invalid rules gracefully and include asset ID/rule ID context in warnings.

## Runtime Flow

Use the existing attachment runtime surface:

- `TameworkAttachmentsComponent` stores persistent selected attachment set values.
- `TameworkDynamicAttachmentsComponent` stores active reversible overlay metadata for `WhileMatching` rules, including baseline values per attachment slot.
- `CompanionAttachmentStateService` validates selections against the current model and migration rules.
- `CompanionModelAttachmentService` applies selected `randomAttachmentIds` through base-game model APIs.

Add focused collaborators:

- `DynamicAttachmentConfigIndex`: immutable role-indexed cache of enabled configs and resolved rules.
- `DynamicAttachmentConditionEvaluator`: pure condition evaluation from an NPC state snapshot.
- `DynamicAttachmentRuleResolver`: resolves winning attachment selections per slot.
- `DynamicAttachmentApplicationService`: applies `Permanent` wins to stored attachments, applies `WhileMatching` wins with baseline tracking, restores inactive temporary slots, and requests existing attachment sync.
- `DynamicAttachmentEvaluationSystem`: low-frequency runtime coordinator.

The system should evaluate on load/bootstrap and on a low-frequency runtime sweep. It should not introduce a broad per-tick mob scan.

`WhileMatching` overlay state must persist across server restarts. If an NPC is saved while a temporary rule is active, then later loads with the condition no longer matching, the system still needs the captured baseline to restore the original attachment slot.

Base-game evidence from Hytale `0.5.6` supports this integration point:

- `ModelAsset#getAttachments(Map<String, String> randomAttachmentIds)` resolves selected attachment set/value IDs into concrete `ModelAttachment` entries.
- `Model#createScaledModel(ModelAsset, float, Map<String, String>)` carries selected random attachment IDs into the runtime model.
- `PersistentDisplayName` persists display names and is seeded into `DisplayNameComponent` on entity load, so display-name matching should prefer persistent names.

## Performance Model

Performance is a core requirement. The design target is negligible cost even when many mobs exist.

Rules:

- No full mob scan every tick.
- Sweep at a low frequency, initially around `1-3s`, with store-scoped jitter or scheduling so many NPCs do not evaluate on the same frame.
- Candidate collection only considers NPC roles present in the current `DynamicAttachmentConfigIndex`.
- Rebuild the role-indexed cache only when `TwDynamicAttachmentsConfig` assets load/remove.
- Pre-sort rules by config priority, rule priority, normalized asset ID, and rule declaration order at cache rebuild time.
- Normalize config strings during cache rebuild, not during every evaluation.
- Avoid streams and avoid avoidable allocations in sweep paths.
- Build a compact fingerprint from only inputs used by rules for that role.
- Skip evaluation when the fingerprint is unchanged.
- Skip persistence and model application when the resolved attachment map is unchanged.
- Skip baseline writes when a `WhileMatching` rule remains active with the same winning slot/value.
- Validate attachment IDs only after a winning rule proposes a changed slot.

Suggested fingerprint inputs:

- role ID
- display name
- owner/tamed state
- gender
- life stage
- trait hash for referenced traits
- happiness value or bucket
- need hash for referenced needs
- command state values referenced by rules

The final implementation may specialize fingerprints by role rule requirements. For example, a role with only `DisplayNameEquals` rules should not read traits, needs, happiness, or command state.

## ECS Safety

Runtime system classes must not directly call `store.putComponent`, `store.removeComponent`, `store.tryRemoveComponent`, or `store.addComponent`.

Writes should use an available `CommandBuffer` in system callbacks. If the final implementation cannot access a command buffer for a required path, route the write through a narrow service invoked from an allowed world-thread context and keep `EcsWriteSafetyGuardTest` passing without casual allowlist expansion.

Do not call `PlayerRef.getComponent(Player)` from tick/runtime paths. This feature should not require player component access for its first version.

## Documentation

Implementation should update:

- `docs/Config-Discovery.md`
- `wiki/Modder-Documentation/Start-Here/Config-Discovery-Resolution-and-Inheritance.md`
- `wiki/Modder-Documentation/Config-Reference/`
- `CHANGELOG.md` for player/modder-facing behavior

Docs should explain both persistence modes:

- `Permanent` writes stored attachment selections.
- `WhileMatching` is reversible, captures the pre-rule slot value on first application, and restores that baseline when the rule stops matching.

## Testing

Add focused tests for:

- Config codec and parent fallback behavior.
- Role-indexed config cache construction.
- Priority conflict resolution per attachment slot.
- `DisplayNameEquals` matching using persistent/runtime name fallback.
- At least one progression-backed condition, such as life stage or trait presence.
- Persistent merge behavior: matching rule writes selected slots and preserves unrelated slots.
- `WhileMatching` captures the previous slot value and restores it after the condition stops matching.
- `WhileMatching` removes the slot on restore when the slot was absent before the rule applied.
- `WhileMatching` state survives reload and can restore after a restart.
- `WhileMatching` does not overwrite an externally changed slot during restore unless the current value still matches the dynamic value it wrote.
- No-op behavior when the resolved map is unchanged.
- Invalid attachment IDs are filtered by existing attachment validation paths.
- Runtime cache/fingerprint behavior skips unchanged NPCs.
- ECS guard tests remain green.

Run `./mvnw test` after implementation changes.

## Open Implementation Choices

- The first implementation can limit condition value operators to the listed types above. More operators can be added later without changing the config family or runtime coordinator.
