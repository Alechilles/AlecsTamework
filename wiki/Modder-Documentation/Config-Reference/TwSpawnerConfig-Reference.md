---
title: "TwSpawnerConfig Reference"
order: 17
published: true
draft: false
---
# TwSpawnerConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwSpawnerConfig` binds a capture-and-spawn behavior set to a specific spawner item. It controls which roles can be captured, local owner restrictions, item cooldowns and effects, and filled-item icon and tooltip behavior.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- Scope: item-scoped
- Resolution key: `EmptyItemId`
- Runtime reload: `/tw reloadconfig` reloads spawner assets into the item feature registry

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level object sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays and maps replace the parent value.
- `AllowedRoles.Allowlist`, `AllowedRoles.Denylist`, `IconOverrides`, `IconOverridesByRole`, and `IconOverrideGroups` all replace the parent value when explicitly authored.

## Top-Level Structure
```json
{
  "EmptyItemId": "Spawner_My_Creature",
  "FilledItemId": "*Spawner_My_Creature_State_Filled",
  "IconDefault": "Icons/Spawner_Default.png",
  "AllowedRoles": { "...": "..." },
  "Capture": { "...": "..." },
  "Spawn": { "...": "..." },
  "IconOverrides": [],
  "IconOverridesByRole": {},
  "IconOverrideGroups": [],
  "TooltipMode": "Additive"
}
```

## Section Reference
### `EmptyItemId`
- Required.
- Item id for the empty spawner item that this asset binds to.

### `FilledItemId`
- Optional.
- Filled variant item id used after a successful capture.

### `IconDefault`
- Optional default icon path for filled-item rendering and tooltip presentation.

### `AllowedRoles`
Controls which NPC roles can be captured or spawned with this item.

Accepted `Mode` values:
- `AllowAll`
- `Allowlist`
- `Denylist`

Fields:
- `Mode`
- `Allowlist`
- `Denylist`

### `Capture`
- `ClearsOwner`: legacy/configurable owner-clear behavior; server runtime
  policy may own the effective value.
- `RequireTamed`: requires the NPC to be tamed before capture succeeds.
- `TamesTarget`: allows an eligible wild, unowned target to become the actor's
  owned tamed companion on successful capture.
- `MaxHealthPercent`: optional health threshold rechecked at start and
  completion.
- `RequiredEffectId`: optional entity effect required at start and completion.
- `ChannelAuraEffectId`: optional effect applied for a capture channel.
- `ChannelSoundEvent`: optional one-shot sound played when the Begin channel
  phase succeeds.
- `TamedRoleOverrides`: source-role to stored/tamed-role map used with
  `TamesTarget`.
- `OwnerRestricted`: restricts capture to the owner when ownership exists.
- `RequireOwner`: explicit owner-presence requirement for this item flow.
- `ParticleSystem`
- `SoundEvent`
- `CooldownMs`
- `MaxDistance`
- `ChanceMode`: `Guaranteed` by default; `Probability` opts into the API 0.9
  role capture policy.
- `Power`: non-negative capture-item power.
- `BaseChance`: probability in `[0,1]`.
- `ChancePerPower`: non-negative additive chance per power above the role
  minimum.
- `MinimumChance` / `MaximumChance`: inclusive probability clamps.
- `FailureCooldownMs`: cooldown applied after a resolved failed roll.
- `FailureParticleSystem` / `FailureSoundEvent`: optional failure feedback.
- `SourceConsumption`: `SuccessOnly` preserves ordinary filled-item behavior;
  `ResolvedAttempt` spends one exact source item after either terminal success
  or terminal failure.
- `SuccessDisposition`: `CapturedItem` creates the configured filled item;
  `TameAndCommandLink` keeps the target live and atomically establishes its
  canonical tame/owner/role/profile, population groups, roster membership, and
  first timed lease; `StoreBondedCompanion` creates a durable `STORED` profile
  in the separate bonded authority before retiring the source NPC.
- `BondedRosterId`: required only for `StoreBondedCompanion`; names the
  receiving bonded roster.
- `CommandFamilyId`: required stable owner-scoped family for
  `TameAndCommandLink`; prohibited for `StoreBondedCompanion`.
- `RequiredCommandConfigId`: exact command-config access fence. It is required
  for `StoreBondedCompanion`.
- `RequireCommandAccessItem`: requires a compatible access item before the
  probability roll. It must be `true` for `StoreBondedCompanion`.

`ChanceMode: Guaranteed` preserves deterministic capture and bypasses
`TwCapturePolicyConfig`, including its custom requirements. `Probability`
requires the `CAPTURE_POLICY` capability before an integration treats the flow
as available. `ResolvedAttempt` additionally requires
`CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`, and `TameAndCommandLink` requires
`CAPTURE_TAME_AND_LINK` plus its population/roster/timed dependencies.

`StoreBondedCompanion` instead requires `BONDED_COMPANIONS`. The selected
target role must resolve to exactly one enabled family inside
`BondedRosterId`. A successful operation stores the full snapshot and exact
capture evidence before source cleanup, creates no filled item, and does not
touch the generic command-family, population, timed-summon, generic profile,
or outbox authorities. Missing or ambiguous bonded policy fails closed before
the roll or source spend.

For a channeled bonded-capture item, use `ChannelAuraEffectId` and
`ChannelSoundEvent` for Begin-phase feedback and author one completion effect.
Completion feedback is dispatched only after the durable result publishes;
do not author the same completion particle/sound in two paths.

### `Spawn`
- `OwnerRestricted`: restricts spawn use to the spawner owner when ownership exists on the item.
- `RequireOwner`: explicit owner-presence requirement for this item flow.
- `ParticleSystem`
- `SoundEvent`
- `CooldownMs`
- `MaxDistance`

### `IconOverrides`
Array of conditional icon overrides. Each entry supports:
- `Icon`
- `Attachments`

`Attachments` is a map of attachment-set name to expected attachment option. Tamework uses it to match a captured NPC’s metadata to the correct icon.

### `IconOverridesByRole`
Map of role id to `IconOverrides` arrays. Use it when icon rules differ per role instead of only by attachment combination.

### `IconOverrideGroups`
Ordered array of shared role groups. Use it when multiple roles should share one icon rule set.

Each group supports:

- `Roles`: role ids covered by the group.
- `IconDefault`: optional default icon for roles in the group.
- `Overrides`: attachment-based icon overrides shared by those roles.

Runtime icon lookup checks exact role overrides first, then the first matching shared role group, then that group's `IconDefault`, then global overrides, then top-level `IconDefault`.

### `TooltipMode`
Controls how captured-spawner item display metadata composes the base item description and Tamework detail lines.

Accepted values:
- `Additive`: keep the base item description, add a blank line, and then append the Tamework tooltip
- `Replace`: replace the base description text with Tamework captured-spawner output

The Tamework output starts with a compact companion summary. When the saved data is available, it
includes the companion name, species, abbreviated gender, current level, and maximum level. Female
markers are pink and male markers are blue.

Saved traits appear under a gold Traits header. Each row uses the trait's configured display name and
shows the current value, configured maximum possible value, and signed percentage relative to the
default. Values above the default fade from white toward green. Values below the default fade from
white toward red. Friendly attachment choices from `TwAttachmentDisplayConfig` appear under a cyan
Appearance header.

## Defaults and Cross-System Notes
- The sample asset is in the optional pack at
  `examples/asset-pack/Server/Tamework/Items/Spawners/TwSpawnerExample.json`.
  Install and explicitly enable `Alec's Tamework! Examples` before using it;
  the main Tamework jar does not ship enabled sample assets.
- Captured Tamework names and progression metadata are preserved on the item and restored on spawn.
- Capture owner clearing and spawn owner assignment are settings-owned runtime policy.
- Spawner capture and release preserve canonical profile identity and tool-link
  state through the replacement full-state snapshot. This config only defines
  the author-facing item policy.

## Minimal Example
```json
{
  "EmptyItemId": "Spawner_My_Creature",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [
      "My_Tamed_Wolf"
    ]
  },
  "Capture": {
    "RequireTamed": true
  },
  "Spawn": {
    "OwnerRestricted": true
  }
}
```

## Common Pattern Example
```json
{
  "EmptyItemId": "Spawner_Tamework_Example",
  "FilledItemId": "*Spawner_Tamework_Example_State_Filled",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [
      "Mob_Tamework_Example",
      "Mob_Tamework_Example_Baby"
    ]
  },
  "Capture": {
    "RequireTamed": true,
    "ChanceMode": "Guaranteed",
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  },
  "Spawn": {
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  },
  "TooltipMode": "Additive"
}
```

## Gotchas
- `EmptyItemId` is the resolution key. If two active configs target the same item, selection becomes a config-resolution problem instead of an item-authoring problem.
- `RequireOwner` is an explicit override, not the same thing as `OwnerRestricted`.
- Use `/tw settings` for the global capture/spawn owner-transfer defaults.
- Unset `RequireOwner` values are not equivalent to `false`; they defer to global ownership-requirement defaults.
- `IconOverrides`, `IconOverridesByRole`, and `IconOverrideGroups` are explicit array/map values and replace the parent content when authored in a child asset.
- `/tw reloadconfig` is required after editing spawner configs during development.
- Role-side probability policy belongs in `TwCapturePolicyConfig`, not copied
  into every capture item.
- `BondedRosterId` is valid only with
  `SuccessDisposition: StoreBondedCompanion`.
- Bonded capture never falls back to a filled item or generic tame/link result
  when the bonded authority is unavailable.

## Related Pages
- [Spawner System Guide](/mod/alecs-tamework/spawner-system-guide)
- [Spawner Icon Generation](/mod/alecs-tamework/spawner-icon-generation)
- [TwNameItemConfig Reference](/mod/alecs-tamework/twnameitemconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [TwCapturePolicyConfig Reference](/mod/alecs-tamework/twcapturepolicyconfig-reference)
- [TwBondedCompanionRosterConfig Reference](/mod/alecs-tamework/twbondedcompanionrosterconfig-reference)
- [Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference)
- [Capture Policy API Reference](/mod/alecs-tamework/capture-policy-api-reference)



