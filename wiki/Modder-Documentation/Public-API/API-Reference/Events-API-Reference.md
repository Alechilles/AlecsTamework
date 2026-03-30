---
title: "Events API Reference"
order: 9
published: true
draft: false
---
# Events API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference-index) | [Public API](/mod/alecs-tamework/public-api-index)

> **Experimental API Contract (`0.4.0`)**
> This reference tracks the current `events()` contract in `TameworkApi`.

Capability: `EVENTS`

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

## Event Semantics
- Dispatch is synchronous on the thread that emits the event.
- Listener exceptions are caught and logged so one consumer cannot break others.
- Always close the returned `AutoCloseable` during unload/shutdown.
- Payloads are immutable snapshots (`record` + defensive copies).

## `ConfigReloadedEvent` Families
- `GLOBAL`
- `INTERACTION`
- `COMPANION`
- `SPAWNER`
- `NAME_ITEM`
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

