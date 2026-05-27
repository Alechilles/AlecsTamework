---
title: "Events API Reference"
order: 9
published: true
draft: false
---
# Events API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.6.0`)**
> This reference tracks the current `events()` contract in `TameworkApi`.

Capabilities: `EVENTS`, `COMPANION_XP_EVENTS`

## Entry Point
`TameworkApi.events() -> TameworkEventsApi`

## Subscription API
`<E extends TameworkEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> listener)`

Example:

```java
AutoCloseable handle = api.events().subscribe(NpcProfileChangedEvent.class, event -> {
    // use immutable event snapshot
});
```

## Event Types
- `NpcProfileChangedEvent`
- `NpcCapturedEvent`
- `NpcDeathRecordedEvent`
- `NpcLostRecordedEvent`
- `ConfigReloadedEvent`
- `CompanionXpAwardedEvent`

## Event Semantics
- Dispatch is synchronous on the thread that emits the event.
- Listener exceptions are caught and logged so one consumer cannot break others.
- Always close the returned `AutoCloseable` during unload/shutdown.
- Payloads are immutable snapshots (`record` + defensive copies).
- `CompanionXpAwardedEvent` is emitted only after Tamework accepts an XP award and applies or queues the component write.

## `CompanionXpAwardedEvent`
Use this successful-only event when an integration wants to credit external player progression from companion activity.

Source buckets:
- `FEED`
- `HARVEST`
- `BREEDING`
- `COMBAT_DAMAGE_DEALT`
- `COMBAT_DAMAGE_TAKEN`
- `CUSTOM`

Payload fields:
- `npcUuid`
- `ownerUuid` nullable; treat null as not creditable to a player.
- `toolIds` immutable command-tool ids linked to the NPC.
- `roleId` nullable role id resolved for the award.
- `levelingConfigId` nullable leveling config id used for the award.
- `source`
- `awardedXp`
- `previousLevel`, `currentLevel`, `leveledUp`
- `previousTotalXp`, `currentTotalXp`
- `previousCurrentXp`, `currentXp`
- `nextLevelXp`, `maxLevel`, `atMaxLevel`
- `occurredAtMs`, `emittedAtMs`

## `ConfigReloadedEvent` Families
- `GLOBAL`
- `INTERACTION`
- `COMPANION`
- `SPAWNER`
- `NAME_ITEM`
- `NAMES`
- `COMMAND_ITEM`
- `COOP`
- `HAPPINESS`
- `NEEDS`
- `BREEDING`
- `TRAIT`
- `DEBUG`

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Event Subscription Lifecycle Recipe](/mod/alecs-tamework/event-subscription-lifecycle-recipe)
- [Auto-Register Companion on Capture Event Recipe](/mod/alecs-tamework/auto-register-companion-on-capture-event-recipe)
- [Pause Companion Jobs on Death or Lost Event Recipe](/mod/alecs-tamework/pause-companion-jobs-on-death-or-lost-event-recipe)
- [Keep Companion Cache in Sync with Profile Changed Events Recipe](/mod/alecs-tamework/keep-companion-cache-in-sync-with-profile-changed-events-recipe)
- [Credit External Skill XP from Companion XP Recipe](/mod/alecs-tamework/credit-external-skill-xp-from-companion-xp-recipe)


