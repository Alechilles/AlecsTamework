---
title: "Command UI Renderer and Contributor API Reference"
order: 20
published: true
draft: false
---
# Command UI Renderer and Contributor API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.11.0`)**
> This reference tracks the current `TameworkApi.commandUi()` contract.

The contract has four independent capabilities:

- `COMMAND_UI_RENDERERS` for a custom page layout;
- `COMMAND_UI_CONTRIBUTORS` for namespaced page and row presentation data;
- `COMMAND_UI_CUSTOM_ACTIONS` for contributor-owned server actions; and
- `COMMAND_UI_CUSTOM_FLOWS` for contributor-owned multi-step flows.

Check every capability that your integration needs. Also check
`api.commandUi().available()`. Older and degraded API implementations return a
fail-closed unavailable facade.

## Registration

Register a renderer and each contributor separately:

```java
CommandUiRegistrationResult rendererResult = api.commandUi().registerRenderer(
        "runeteria:husbandry_ui",
        new CommandUiRendererDescriptor(
                Set.of("runeteria:husbandry"),
                Set.of("runeteria:husbandry/checklist")),
        HusbandryCommandUiController::new);

CommandUiRegistrationResult contributorResult = api.commandUi().registerContributor(
        "runeteria:husbandry",
        new CommandUiContributorDescriptor(
                Set.of("runeteria:husbandry/page"),
                Set.of("runeteria:husbandry/row"),
                Set.of(CommandUiContributorAction.Scope.PAGE,
                        CommandUiContributorAction.Scope.ROW,
                        CommandUiContributorAction.Scope.FLOW),
                Set.of("runeteria:husbandry/checklist")),
        HusbandryPresentationContributor::new);
```

Renderer and contributor IDs are lowercase namespaced IDs. The `tamework:`
namespace is reserved. A registration does not replace a live registration
with the same ID.

`CommandUiRegistrationResult.Status` values are `REGISTERED`, `CONFLICT`,
`INVALID`, and `UNAVAILABLE`. Keep each returned `CommandUiRegistration` as
plugin state. Close contributor registrations before the renderer registration
when the plugin stops. A handle closes only its exact registration generation.

The renderer descriptor declares the contributor namespaces or exact IDs and
custom flow types that it can display. The contributor descriptor declares its
page-data namespaces, row-data namespaces, action scopes, and custom flow
types. Tamework checks these declarations before it creates the page.

The registration overloads without descriptors are unrestricted compatibility
overloads. New integrations should use explicit descriptors.

## Command Config Selection

Select one renderer and zero or more contributors in the effective
`TwCommandItemConfig`:

```json
{
  "UiRendererId": "runeteria:husbandry_ui",
  "UiContributors": [
    {
      "Id": "runeteria:husbandry",
      "Required": true
    },
    {
      "Id": "runeteria:seasonal_badge",
      "Required": false
    }
  ]
}
```

If the renderer ID is omitted, blank, invalid, or not registered, Tamework
uses its standard command menu. A missing, incompatible, or failed required
contributor also causes standard-menu fallback. An optional contributor can be
missing, incompatible, removed, or failed while the custom page continues.
Its contribution has a status that explains the unavailable state.

This selection is per effective command config. A renderer registration does
not affect command items that select a different renderer or no renderer.

## Renderer Lifecycle

`CommandUiRendererProvider.create(CommandUiOpenContext)` returns one
`CommandUiPageController<?>` for each opened menu.

`CommandUiOpenContext` contains only detached presentation context: player
UUID, language, tool ID, config ID, renderer ID, and roster mode. It does not
expose a live player, ECS reference, item, or gameplay callback.

A controller supplies its fixed event `BuilderCodec` and can implement:

- `buildInitial(...)` to append UI assets and render the first snapshot;
- `update(...)` to change only affected UI components;
- `handleEvent(...)` to route page events; and
- `close()` to release local state and listeners.

Tamework owns the page, session, current-world dispatch, action authority, and
failure boundary. A renderer creation or initial-build failure opens the
standard command menu. A later page failure closes that session without
removing the renderer registration.

Paths passed to `UICommandBuilder.append(...)` are relative to
`Common/UI/Custom`. For example, an asset stored at
`Common/UI/Custom/Rune_UI/HusbandryCommand.ui` must be appended as
`Rune_UI/HusbandryCommand.ui`. Do not include `Common/UI/Custom/` in the
runtime path.

## Snapshot and Partial Updates

`CommandUiSnapshot` is a full immutable presentation snapshot. It contains
command choices, hotswap choices, companion rows, panel state, built-in action
views, timing data, and separate presentation and action generations. It also
contains one `CommandUiContribution` per configured contributor.

Each contribution has isolated page data, row data keyed by stable row UUID,
page actions, command actions, row actions, flow actions, and a lifecycle
status. A renderer reads values through the contributor ID. One contributor
cannot overwrite another contributor's namespace.

`CommandUiUpdate` always carries the new full snapshot. Its
`CommandUiChangeSet` identifies a full refresh, changed sections, changed row
IDs, and removed row IDs. These are rendering hints. A renderer can ignore the
hints and compare snapshots, or it can update only one selector, such as an
indicator on one card. The host submits updates with `clear=false`.

Tamework coalesces live signals. A contributor can request focused composition
through its `CommandUiContributorDirtySink`:

- `markPageDirty()` for page values;
- `markRowsDirty(ids)` for selected companion rows;
- `markPathsDirty(paths)` for contributor-local paths; or
- `markAllDirty()` for the full contributor namespace.

Dirty scopes retain at most 256 paths or row IDs. Overflow becomes a full
contributor refresh. Presentation-only refreshes retain valid opaque action
handles when the action surface is unchanged.

## Contributor Lifecycle and Data

`CommandUiContributorProvider.create(CommandUiContributorCreateContext)`
returns one `CommandUiSessionContributor` for the open menu. The context gives
the detached open context, contributor ID, exact registration generation, and
dirty sink.

`compose(base, previous, scope)` returns the complete current contribution for
that contributor. Use `previous` and `scope` to reduce your own calculation,
but return a complete value for the affected contribution surface. Do not
retain live Hytale objects in a contributor.

Contribution data uses `CommandUiValue`. Tamework rejects a contribution that
exceeds any limit:

| Limit | Contribution data | Action input |
| --- | ---: | ---: |
| Maximum value depth | 8 | 4 |
| Maximum value nodes | 2,048 | 64 |
| Maximum children in one list or object | 256 | 32 |
| Maximum key length | 128 | 64 |
| Maximum total string characters | 65,536 | 4,096 |

The contribution character budget includes page and row keys. A contribution
can contain at most 256 row entries. The limits apply to each complete
contribution or action input, not to the full page across all contributors.

Composition callbacks that take more than 10 ms are counted as slow. Tamework
logs at most one slow-composition warning per contributor per minute.

## Actions

Built-in Tamework actions and contributor actions both reach the renderer as
detached `CommandUiActionView` values. An enabled view can contain an opaque,
session-bound `CommandUiActionHandle`. Pass only its token through the client
event. Never send or trust a client-supplied target, route, owner, profile,
cost, or action kind.

Invoke a handle with `session.invoke(handle)`. For an action that accepts text,
use `session.invoke(new CommandUiActionRequest(handle, input))`. Tamework
checks the declared input policy and the action-input limits before it invokes
the server handler.

A contributor defines actions with `CommandUiContributorAction`. Its local ID
becomes an effective ID under the contributor namespace. Actions can use
`PAGE`, `COMMAND`, `ROW`, or `FLOW` scope and `NONE`, `OPTIONAL`, or `REQUIRED`
input policy. The handler remains on the server. Tamework checks the renderer
generation, contributor generation, session, action generation, scope,
authority, input, and confirmation state before each invocation.

`CommandUiActionStatus` values are `APPLIED`, `ACCEPTED`,
`CONFIRMATION_REQUIRED`, `DENIED`, `STALE`, `NOT_FOUND`, `UNAVAILABLE`,
`CONFLICT`, and `FAILED`. A confirmation response supplies a new short-lived
handle. Bind that handle to the confirmation control; do not reuse the first
handle.

## Built-in and Custom Flows

Built-in group and talent actions can return `CommandUiGroupFlowView` and
`CommandUiTalentFlowView`. These detached views include their current
server-owned action handles.

A contributor can return `CommandUiCustomFlowView` through an `OPEN`,
`REPLACE`, `UPDATE`, or `CLOSE` flow operation. The flow has a namespaced type,
stable instance ID, contributor owner ID and generation, revision, action
generation, detached data, and flow-scoped actions. Tamework accepts the flow
only from its registered owner and binds its server action definitions to new
opaque handles. One session owns at most one active custom flow.

Use `REPLACE` for a new step or action surface. Use `UPDATE` for data changes
within the same flow. Use `CLOSE` to return to the retained main snapshot.
Removing the contributor registration invalidates its flow and handles.

## Session, Fallback, and Cleanup

`CommandUiSession` is valid for one open page. It supplies the latest snapshot,
action invocation, refresh requests, event routing, and a guarded partial
update sink. Closing it closes the host, controller, contributors, flow, and
all issued handles.

Tamework invalidates handles when the session closes, an owning registration
generation ends, authority is lost, or the relevant action generation changes.
An invalid or stale handle fails closed.

`CommandUiApi.diagnostics()` returns immutable, redacted registration and
active-session data. It includes IDs, exact generations, contributor statuses,
composition counts and timings, safe failure reasons, and slow-callback counts.
It never exposes action tokens, action input, mutable runtime objects, or
private contribution values.

## Related Pages

- [Register a Custom Command UI Recipe](/mod/alecs-tamework/register-a-custom-command-ui-recipe)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [Command Items](https://github.com/AlecHilles/Tamework/blob/main/docs/Command-Items.md)
