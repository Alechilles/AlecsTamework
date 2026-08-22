---
title: "Events API Reference"
order: 10
published: true
draft: false
---
# Events API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
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
- `CaptureAttemptResolvedEvent`
- `PopulationGroupMembershipChangedEvent`
- `PopulationGroupLimitChangedEvent`
- `CommandFamilyRosterMembershipChangedEvent`
- `CommandTimedSummoningChangedEvent`
- `CompanionProvisionedEvent`
- `ProvisionedCompanionDeathRecordedEvent`
- `ProvisionedCompanionRevivedEvent`
- `PaidCommandRevivedEvent`
- `ConfigReloadedEvent`
- `CompanionXpAwardedEvent`

## Event Semantics
- Dispatch is synchronous on the thread that emits the event.
- Listener exceptions are caught and logged so one consumer cannot break others.
- Always close the returned `AutoCloseable` during unload/shutdown.
- Payloads are immutable snapshots (`record` + defensive copies).
- Persistence semantic events are published from one checkpointed outbox
  observer. Replay after restart does not redeliver an already checkpointed
  event to the same runtime consumer.
- `CompanionXpAwardedEvent` is emitted only after Tamework accepts an XP award and applies or queues the component write.
- Companion XP does not require a command-tool link; command links only add optional tool id context.

## Activity API V2

`TameworkApi.activities()` provides a process-local, filtered activity feed.
It does not retain or replay activities. Consumers must close their
subscription during shutdown.

`RevivalActivityView` includes the persistent companion profile identity and,
when Tamework can resolve the active managed role, `roleId` and immutable
`groupIds`. The role is nullable and the group set can be empty for older or
unmanaged revival routes. Consumers must ignore a revival when they require
family policy data and these fields are unavailable. Check the
`REVIVAL_ACTIVITY_CONTEXT` capability before relying on these fields.

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
- `toolIds` immutable command-tool ids linked to the NPC; empty when the companion is not linked to a command tool.
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
- `MOUNTED_GLIDE`
- `AVATAR_FLIGHT`
- `COMPANION`
- `SPAWNER`
- `NAME_ITEM`
- `NAMES`
- `COMMAND_ITEM`
- `COOP`
- `FOOD`
- `HAPPINESS`
- `NEEDS`
- `BREEDING`
- `ATTACHMENT_MIGRATION`
- `ATTACHMENT_DISPLAY`
- `DYNAMIC_ATTACHMENTS`
- `LEVELING`
- `TRAIT`
- `TALENT`
- `DEBUG`
- `CAPTURE_POLICY`
- `POPULATION_GROUP`

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Event Subscription Lifecycle Recipe](/mod/alecs-tamework/event-subscription-lifecycle-recipe)
- [Auto-Register Companion on Capture Event Recipe](/mod/alecs-tamework/auto-register-companion-on-capture-event-recipe)
- [Pause Companion Jobs on Death or Lost Event Recipe](/mod/alecs-tamework/pause-companion-jobs-on-death-or-lost-event-recipe)
- [Keep Companion Cache in Sync with Profile Changed Events Recipe](/mod/alecs-tamework/keep-companion-cache-in-sync-with-profile-changed-events-recipe)
- [Credit External Skill XP from Companion XP Recipe](/mod/alecs-tamework/credit-external-skill-xp-from-companion-xp-recipe)


