---
title: "Ownership Policy and Core Builders"
order: 9
published: true
draft: false
---
# Ownership Policy and Core Builders

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Use this page when you need the shared building blocks rather than one specific feature family.

## Core NPC action builders
- `TameworkInteract`
- `TameworkInteractPrompt`
- `TameworkCaptureOwner`
- `TameworkCaptureStranger`
- `TameworkCaptureWild`
- `TameworkDenyCaptureUntamed`
- `TameworkDenyInteract`
- `TameworkSetOwner`
- `TameworkSetTamed`
- `TameworkNeedsResourceConsume`
- `TameworkHarvestDrop`
- `TameworkHarvestAlarm`
- `TameworkDebugMessage`

`TameworkHarvestAlarm` sets the `Harvest_Ready` alarm from the role's `HarvestTimeout` builder parameter and applies the companion's `HarvestCooldownMultiplier` passive effect before scheduling the alarm.

## Core sensors and filters
- `TameworkIsOwner`
- `TameworkHasOwner`
- `TameworkIsTamed`
- `TameworkLifeStage`
- `TameworkHook`
- `TameworkEffectActive`
- `TameworkNeedBelow`
- `TameworkNeedsResourceTarget`
- `TameworkAttitudeFromTargetSlot` (checks a candidate NPC's attitude toward a marked target slot)
- `TameworkAttackedTargetSlotRecently`
- `TameworkInteractionActive` (entity filter; see held interactions below)

## Peaceful positioning and held interactions (development)

These builders require the Tamework development build that adds teaser support.

`TameworkMaintainDistance` is a body motion that reuses native `MaintainDistance`
steering with a fixed `PositioningAngle` in degrees, from -180 to 180. The default
180 places the NPC in front of the sensed target; 0 places it behind. It follows
the target's body yaw. Use a target-producing sensor and a Walk motion controller.

```json
{
  "Sensor": { "Type": "Target", "TargetSlot": "PlayOwner" },
  "BodyMotion": {
    "Type": "TameworkMaintainDistance",
    "PositioningAngle": 180,
    "DesiredDistanceRange": [1.3, 1.8],
    "RelativeForwardsSpeed": 0.35,
    "RelativeBackwardsSpeed": 0.25
  },
  "HeadMotion": { "Type": "Watch" }
}
```

This motion always uses its configured angle and distance range; combat sensors
do not override them. It does not choose targets, mark enemies, or run a combat
action evaluator. Use normal `Seek` for approach/pathfinding and this motion for
nearby positioning. Obstacle handling still needs testing for each NPC.

`TameworkInteractionActive` requires an exact `RootInteractionId` and matches
while the candidate has an unfinished server interaction chain started by that
root. Finished and cancelled chains do not match. Combine it with `ItemInHand`
and `TameworkIsOwner` when only the NPC's owner should trigger play:

```json
{
  "Type": "TameworkInteractionActive",
  "RootInteractionId": "AlecsCats_Teaser_Use"
}
```

Place this filter under a `Player` or `Target` sensor's `Filters`. For held Use,
the item's root can run an indefinite `Charging` interaction. The filter reads
the candidate's active chains on the NPC world thread and creates no effects,
timers, or persistent player state. It does not enforce ownership by itself.

## Core runtime components
- `TameworkOwnerComponent`
- `TameworkTamedComponent`
- `TameworkHookComponent`
- `TameworkNpcNameComponent`
- `TameworkCommandLinksComponent`
- `TameworkHappinessComponent`
- `TameworkNeedsComponent`
- `TameworkBreedingComponent`
- `TameworkTraitsComponent`
- `TameworkAttachmentsComponent`
- `TameworkLifeStageComponent`

## Ownership guidance
- Global defaults belong in `TwGlobalConfig`
- Ownership requirement defaults live under `TwGlobalConfig.OwnershipRequirements`
- Role-specific ownership and command protection belong in `TwCompanionConfig`
- Item systems should not re-implement their own ownership rules unless the item config explicitly needs a stricter or looser override

`TameworkSetOwner` and Tame check durable owner-population admission before
assigning a non-null owner. The cap counts canonical owned profiles in the
configured global/per-world scope and reserves positive capacity within the
same shared operation. It remains independent from SimpleClaims
claim-placement policy.

`TameworkOwnerComponent` is authoritative. Clearing or transferring canonical ownership invalidates
the prior command-link authority, while retained name metadata follows the new canonical owner.

## Related Pages
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)



