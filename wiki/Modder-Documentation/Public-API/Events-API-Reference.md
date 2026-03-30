---
title: "Events API Reference"
order: 9
published: true
draft: false
---
# Events API Reference

Parent: [Public API Index](/mod/alecs-tamework/public-api-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

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

