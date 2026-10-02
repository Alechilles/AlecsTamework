---
title: "Events API Reference"
order: 10
published: true
draft: false
---
# Events API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> Companion events now come from the companion store's change listener.
> `NpcProfileChangedEvent` has four new fields and `ProfileChangeType` has two
> new values. Events are not replayed after a restart.

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

Published in 3.0.0:

- `NpcProfileChangedEvent`
- `NpcCapturedEvent`
- `NpcDeathRecordedEvent`
- `NpcLostRecordedEvent`
- `CaptureAttemptResolvedEvent`
- `BondedCompanionCaptureResolvedEvent`
- `ConfigReloadedEvent`
- `CompanionXpAwardedEvent`

No longer published in 3.0.0, because their APIs were removed or replaced:

- `PopulationGroupMembershipChangedEvent` and
  `PopulationGroupLimitChangedEvent`. Use the `groupIds` and location kinds on
  `NpcProfileChangedEvent`, and `ConfigReloadedEvent` for `POPULATION_GROUP`.
- `CommandTimedSummoningChangedEvent`, `CompanionProvisionedEvent`,
  `ProvisionedCompanionDeathRecordedEvent`,
  `ProvisionedCompanionRevivedEvent`, and `PaidCommandRevivedEvent`. Use
  `NpcProfileChangedEvent`, or `BondedCompanionApi.subscribe` for bonded
  companions.
- `CommandFamilyRosterMembershipChangedEvent`. `commandFamilyRosters()` was
  removed and roster membership has no public replacement.

A subscription to one of these types is accepted and never called.

## Delivery

- Companion events are delivered after the change is applied and after the
  companion store's lock is released, so a listener may read the API.
- They are delivered on the thread that made the change. That is often a world
  thread, but it can be the store's writer thread or another thread. Do not
  assume a world thread. Hop to the owning world with `world.execute(...)`
  before touching entities.
- Changes made by one thread are delivered in the order that thread made them.
  There is no order guarantee between changes made on different threads.
- A change that is undone (for example because the file write failed) is
  followed by an event for the compensating change. A subscriber can see a
  state that is reverted a moment later.
- Events are published when the change is applied in memory. They do not wait
  for the file write.
- Events are live notifications. Nothing is stored or replayed after a
  restart.
- A listener must not block.
- Listener exceptions are caught and logged so one consumer cannot break
  others.
- Always close the returned `AutoCloseable` during unload or shutdown.
- Payloads are immutable snapshots.

## `NpcProfileChangedEvent`

Published for every change a subscriber can see: created or tamed, released or
culled, owner changed, renamed, role changed, and every change of where the
companion is. Position, summary, and timer refreshes publish nothing.

| Field | Meaning |
| --- | --- |
| `profileId` | The companion's profile ID. |
| `changeTypes` | What changed. See below. |
| `before` | The profile before. Null for a new profile. |
| `after` | The profile after. Null for a released one. |
| `emittedAtMs` | Wall-clock time. |
| `oldLocationKind` | New in 3.0.0. Null for a new profile. |
| `newLocationKind` | New in 3.0.0. |
| `groupIds` | New in 3.0.0. The population groups of the companion's role. |
| `domainClaims` | New in 3.0.0. The admission-provider claims the companion holds after the change. For a release, the claims it gave up. |

Location kinds are `LIVE`, `ITEM`, `COOP`, `STORED`, `DEAD`, `LOST`, and
`RELEASED`.

`ProfileChangeType` values: `CREATED`, `CURRENT_NPC_UUID`, `OWNER`, `ROLE`,
`DISPLAY_NAME`, `CUSTOM_NAME`, `TAMED`, `COOP_ASSIGNMENT`, `TOOL_LINKS`,
`ACTIVE_SNAPSHOTS`, and two new in 3.0.0:

- `LOCATION`: the companion moved between location kinds, for example `LIVE`
  to `STORED`.
- `RELEASED`: the companion was released or culled and no longer counts for
  its owner.

A change of domain claims alone publishes an event with an empty
`changeTypes`.

The five-argument constructor from 2.x still exists. It leaves the kinds null
and the sets empty.

## Holder events

- `NpcCapturedEvent`: a companion went into a capture item from the world.
- `NpcDeathRecordedEvent`: a companion died.
- `NpcLostRecordedEvent`: a companion's body vanished without a confirmed
  death.

These follow `NpcProfileChangedEvent` for the same change. Home positions are
null in 3.0.0, and the lost event's relocation fields are zero.

## Capture events

`CaptureAttemptResolvedEvent` is published for a capture that succeeds and for
a failed roll that spent its source item.

- For a capture into an item it is published from the hand-over, on the
  body's world thread, once the body is known to be there. When the hand-over
  does not happen (the body vanished first, or a newer change replaced the
  capture) no event is published.
- For a capture into bonded storage it is published once the capture is saved,
  together with `BondedCompanionCaptureResolvedEvent`, even when the body has
  vanished.

There is no replay evidence in 3.0.0. The attempt ID is also the operation ID.

## Activity API V2

`TameworkApi.activities()` provides a process-local, filtered activity feed.
It does not retain or replay activities. Consumers must close their
subscription during shutdown.

Managed activity profiles can publish `tamework:cull_success` as a
`ManagedActivityView`. The payload contains one owner/companion participant,
the resolved family group, the profile's mapped activity ID, and the domestic
item quantities. A profile that omits cull configuration publishes no cull
activity and keeps normal death drops.

`RevivalActivityView` includes the persistent companion profile identity and,
when Tamework can resolve the active managed role, `roleId` and immutable
`groupIds`. The role is nullable and the group set can be empty for older or
unmanaged revival routes. Consumers must ignore a revival when they require
family policy data and these fields are unavailable. Check the
`REVIVAL_ACTIVITY_CONTEXT` capability before relying on these fields.

## `CompanionXpAwardedEvent`
Use this successful-only event when an integration wants to credit external player progression from companion activity.

- It is emitted only after Tamework accepts an XP award and applies or queues the component write.
- Companion XP does not require a command-tool link; command links only add optional tool id context.

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
