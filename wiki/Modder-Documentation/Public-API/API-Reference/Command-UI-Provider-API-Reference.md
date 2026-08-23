---
title: "Command UI Provider API Reference"
order: 20
published: true
draft: false
---
# Command UI Provider API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> This reference tracks the current `commandUi()` contract in `TameworkApi`.

Capability: `COMMAND_UI_PROVIDERS`

## Entry Point

`TameworkApi.commandUi() -> CommandUiApi`

Use `available()` and the capability before registration. Older API
implementations return a fail-closed unavailable facade.

## Provider Registration

```java
CommandUiProviderRegistrationResult result = api.commandUi().register(
        "runeteria:husbandry",
        context -> new HusbandryCommandPage(context)
);
```

Provider IDs are lowercase namespaced IDs. The `tamework:` namespace is
reserved. Registration does not replace a live provider with the same ID.

`CommandUiProviderRegistrationResult.Status` values:

- `REGISTERED`
- `CONFLICT`
- `INVALID`
- `UNAVAILABLE`

Keep the returned `CommandUiProviderRegistration`. Close it when the plugin
stops. The handle closes only its exact registration generation.

## Provider and Controller

`CommandUiProvider.create(CommandUiOpenContext)` returns one
`CommandUiPageController<?>` for each opened menu.

`CommandUiOpenContext` contains detached presentation context:

- player UUID and language;
- tool and config IDs;
- provider ID; and
- roster mode (`generic` or `bonded`).

It does not expose a live player, ECS reference, item, or gameplay callback.

A controller supplies its event `BuilderCodec` and can implement:

- `buildInitial(...)` to append its UI assets and render the first snapshot;
- `update(...)` to change only affected UI components;
- `handleEvent(...)` to route provider page events; and
- `close()` to release provider-local state.

Tamework owns the page, session, world dispatch, and failure boundary. If a
custom controller cannot be created or its initial build fails, Tamework opens
the standard command menu. A failure in one open custom page does not remove
the provider registration.

## Snapshot Contract

`CommandUiSnapshot` is a full immutable presentation snapshot. It contains:

- command choices and selected command;
- Q/E/R assignments and choices;
- companion rows and their presentation values;
- panel mode, radius, sort, filter, and group state;
- global, command, panel, and per-row Tamework actions;
- server time and visible deadlines; and
- separate presentation and action generations.

The snapshot is detached. Do not treat it as gameplay authority.

## Partial Updates

Each `CommandUiUpdate` contains the new full snapshot, the prior snapshot when
available, and a `CommandUiChangeSet`.

The change set can identify:

- a full refresh;
- changed snapshot sections;
- changed companion row IDs; and
- removed companion row IDs.

These values are rendering hints. A provider can ignore them and compare the
full snapshots. For a small indicator change, update only the selector for that
indicator. Tamework sends provider updates with `clear=false`, so the rest of
the page stays in place.

Tamework coalesces live signals and also gives custom pages periodic
presentation refreshes. Presentation-only refreshes keep valid action handles.
If the visible action surface changes, Tamework issues a new action generation.

## Actions and Events

Version 1 exposes only Tamework-defined actions. Each enabled
`CommandUiActionView` can contain an opaque, session-bound
`CommandUiActionHandle`.

Pass only that token through the provider event payload. Call
`CommandUiSession.invoke(handle)` or `handleEvent(CommandUiEvent)`. Do not send
a target, route, owner, roster, profile, or cost from the client. Tamework
holds that authority and validates it again in the player's current world.

`CommandUiActionStatus` values are `APPLIED`, `ACCEPTED`,
`CONFIRMATION_REQUIRED`, `DENIED`, `STALE`, `NOT_FOUND`, `UNAVAILABLE`,
`CONFLICT`, and `FAILED`. `APPLIED` confirms a state change. `ACCEPTED` means
that Tamework dispatched a legacy callback but could not confirm its result.
Both values can cause a presentation refresh. Destructive and paid actions can
return a new confirmation handle. Invoke that new handle only after the
provider shows its confirmation flow.

## Session and Cleanup

`CommandUiSession` is valid for one open page. It provides the latest snapshot,
action invocation, refresh requests, and a guarded update sink.

The update sink can request a Tamework snapshot refresh or submit a
provider-local partial UI update. The host always forces partial submission to
`clear=false`.

Tamework invalidates all handles when the session closes, the provider
generation ends, authority is lost, or an action generation changes.

## Asset Selection

Set `UiProviderId` in the effective `TwCommandItemConfig`:

```json
{
  "UiProviderId": "runeteria:husbandry"
}
```

If the field is omitted, blank, invalid, unavailable, or not registered,
Tamework uses its standard command menu.

## Related Pages

- [Register a Custom Command UI Recipe](/mod/alecs-tamework/register-a-custom-command-ui-recipe)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [Command Items](https://github.com/AlecHilles/Tamework/blob/main/docs/Command-Items.md)
