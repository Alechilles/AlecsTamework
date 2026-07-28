---
title: "Progression Systems Guide"
order: 10
published: true
draft: false
---
# Progression Systems Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

This guide explains how the progression families fit together and how to roll them out in a sane order. Use the reference pages for exact field lists.

## The Main Families
- [TwLevelingConfig Reference](/mod/alecs-tamework/twlevelingconfig-reference): XP sources, level curve, stat growth, and talent point grants
- [TwTalentConfig Reference](/mod/alecs-tamework/twtalentconfig-reference): passive talent trees, prerequisites, and effect multipliers
- [TwHappinessConfig Reference](/mod/alecs-tamework/twhappinessconfig-reference): mood baseline, convergence, and modifiers
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference): hunger and thirst
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference): breeding readiness, cooldowns, inheritance, and lifecycle
- [TwAttachmentMigrationConfig Reference](/mod/alecs-tamework/twattachmentmigrationconfig-reference): deterministic upgrades for newly split model attachment slots
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference): trait pools and inherited stat variation

## Recommended Rollout Order
### 1. Start with leveling if the species should progress in combat or care
Set the long-term progression baseline first:
- max level
- XP curve
- which actions award XP
- which stats scale per level
- how many talent points each level grants

This gives you a shared advancement layer that combat, feed, harvest, and breeding hooks can all contribute to.

Tamework ships `Server/Tamework/Leveling/TwLevelingExample.json` for the bundled example mobs. Use it as a small working baseline for enabled feed, harvest, breeding, and combat XP sources before tuning a real species.

### Avatar-flight XP
Avatar-flight species can also award companion XP by configuring `TwLevelingConfig.XpSources.Flight` on the original parked source role:

```json
"Flight": {
  "Enabled": true,
  "XpPerQualifiedSecond": 0.15,
  "AwardIntervalSeconds": 10.0,
  "MaxXpPerMinute": 9.0
}
```

`Enabled`, `XpPerQualifiedSecond`, `AwardIntervalSeconds`, and `MaxXpPerMinute` are the complete field set. The defaults are inert: flight awards require `Enabled: true`, a positive rate, interval, and cap; the default interval is `10.0` seconds while the default rate and cap are zero. Tamework qualifies only time that the controller reports as active fast flight, so this is independent of distance travelled, input held, or idle time. It accumulates at most `0.25` seconds per server tick, awards completed 10-second batches in the example above, and enforces the configured rolling `9 XP` per minute limit.

The source bucket is `AVATAR_FLIGHT`, which is normal companion XP, not player XP. Awards target the valid parked source companion using its original role's leveling config. A missing, stale, mismatched, or otherwise invalid flight session receives no XP rather than attempting a fallback recipient.

### 2. Add talents when level-ups should unlock player choices
Add `TwTalentConfig` after the level curve is stable.

Decide:
- what passive nodes exist
- point costs and minimum levels
- prerequisite chains
- which shared effect keys each node grants

Tamework ships `Server/Tamework/Talents/TwTalentsExample.json` for the bundled example mobs. It is intentionally passive-only and demonstrates health, movement, combat, toughness, and harvest-bonus effect keys.

Avatar-flight talent trees can additionally use the six flight effect keys documented in the [TwTalentConfig Reference](/mod/alecs-tamework/twtalentconfig-reference). They are resolved from the parked source companion for a valid flight session; invalid sessions remain neutral.

The in-game talent page presents these nodes as a scrollable branch/tier tree. Keep node names compact, put long explanations in `Description`, and use `RequiresTalentIds[]` for every visible dependency line you expect players to follow.

### 3. Start with happiness
Set the baseline first:
- current default
- min and max
- feed and pet gains
- owner-nearby and population modifiers

This gives you a shared “wellbeing” layer that other systems can read.

### 4. Add needs if you want upkeep
Add hunger and thirst when the species should require care over time.

Decide:
- how fast hunger and thirst decay
- whether nearby container feeding or nearby water should work
- whether manual feed and water-bucket interactions should refill needs
- whether neglected companions should take damage

### 5. Add breeding when long-term progression matters
Only after happiness and adulthood rules are clear should you add breeding.

Decide:
- minimum happiness threshold
- pairing rules and owner restrictions
- cooldown windows
- passive breeding cadence
- what offspring inherits

### 6. Add traits when inheritance should create variation
Traits are most valuable once breeding is already stable.

Decide:
- how many traits can roll
- whether duplicates are allowed
- inheritance and mutation chance
- effect keys and icon presentation

## Cross-System Interactions
### Feed interactions
Feed interactions often touch three systems at once:
- direct heal or item consumption from `TwInteractionConfig`
- happiness gain from `TwHappinessConfig`
- manual hunger or thirst refill from `TwNeedsConfig`

### Breeding readiness
Breeding depends on more than one family:
- happiness threshold from `TwBreedingConfig.Happiness`
- actual happiness value from `TwHappinessConfig`
- adulthood and lifecycle state from `TwBreedingConfig.OffspringLifecycle`
- trait inheritance only if `TwTraitConfig` is present and breeding inheritance allows it

### Linked panel and UI
The linked panel can surface:
- level progress and unspent talent points
- talent tree access when the current companion is loaded, including a scrollable tree view with node state, prerequisites, effect summaries, and selected-node purchase details
- a talent reset action for loaded companions that have spent points
- happiness and needs state
- breeding cooldown or readiness
- traits and life-stage data

That means incomplete progression configs usually show up as missing or flat UI output before they show up as crashes.

## Species Authoring Checklist
1. Resolve the role ids that should share progression behavior.
2. Author `TwLevelingConfig` if the species should gain XP or levels.
3. Add `TwTalentConfig` when players should spend points on passive upgrades.
4. Author `TwHappinessConfig` once the wellbeing layer is intentional.
5. Add `TwNeedsConfig` only if the species should require upkeep.
6. Add `TwBreedingConfig` only after adulthood and role-family expectations are clear.
7. Add `TwTraitConfig` once inheritance and effect keys are intentional.
8. Test with live commands, combat, and linked-panel output, not only raw JSON review.

## Fast Debug Workflow
Use:
- `/tw gethappiness`
- `/tw sethappiness`
- `/tw getneeds`
- `/tw setneeds`
- `/tw gettraits`
- `/tw settraits`
- `/tw addtrait`
- `/tw getlifestage`

For breeding issues, also verify:
- effective happiness threshold
- adult gating
- cooldown state
- sleep and combat gates

For needs-damage diagnostics, use:
- `/tw debugneedsdamage [on|off]`

For needs seek/targeting diagnostics, use:
- `/tw debugneedsseek [on|off]`

For telemetry-backed needs failure aggregation, use:
- `/tw debugneedstelemetry [on|off]`

## Related Pages
- [TwHappinessConfig Reference](/mod/alecs-tamework/twhappinessconfig-reference)
- [TwLevelingConfig Reference](/mod/alecs-tamework/twlevelingconfig-reference)
- [TwTalentConfig Reference](/mod/alecs-tamework/twtalentconfig-reference)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference)
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)



