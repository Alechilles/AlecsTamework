---
title: "Register a Custom Command UI Recipe"
order: 24
published: true
draft: false
---
# Register a Custom Command UI Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Public API](/mod/alecs-tamework/public-api)

Goal: let one Java plugin render a custom command menu and let one or more
plugins add namespaced presentation, actions, and flows. Tamework keeps
gameplay authority.

## 1. Check the contract

```java
EnumSet<TameworkApiCapability> required = EnumSet.of(
        TameworkApiCapability.COMMAND_UI_RENDERERS,
        TameworkApiCapability.COMMAND_UI_CONTRIBUTORS,
        TameworkApiCapability.COMMAND_UI_CUSTOM_ACTIONS,
        TameworkApiCapability.COMMAND_UI_CUSTOM_FLOWS);

if (!api.getCapabilities().containsAll(required)
        || !api.commandUi().available()) {
    return;
}
```

Check only the capabilities that your plugin needs. A data-only contributor
does not need the action or flow capability.

## 2. Register the renderer and contributor

Keep every successful registration as plugin state.

```java
private CommandUiRegistration rendererRegistration;
private CommandUiRegistration contributorRegistration;

void registerCommandUi(TameworkApi api) {
    CommandUiRegistrationResult renderer = api.commandUi().registerRenderer(
            "runeteria:husbandry_ui",
            new CommandUiRendererDescriptor(
                    Set.of("runeteria:husbandry"),
                    Set.of("runeteria:husbandry/checklist")),
            HusbandryCommandUiController::new);
    if (!renderer.registered()) return;
    rendererRegistration = renderer.registration();

    CommandUiRegistrationResult contributor =
            api.commandUi().registerContributor(
                    "runeteria:husbandry",
                    new CommandUiContributorDescriptor(
                            Set.of("runeteria:husbandry/page"),
                            Set.of("runeteria:husbandry/row"),
                            Set.of(CommandUiContributorAction.Scope.PAGE,
                                    CommandUiContributorAction.Scope.ROW,
                                    CommandUiContributorAction.Scope.FLOW),
                            Set.of("runeteria:husbandry/checklist")),
                    HusbandryPresentationContributor::new);
    if (contributor.registered()) {
        contributorRegistration = contributor.registration();
    } else {
        rendererRegistration.close();
        rendererRegistration = null;
    }
}

void unregisterCommandUi() {
    if (contributorRegistration != null) contributorRegistration.close();
    if (rendererRegistration != null) rendererRegistration.close();
    contributorRegistration = null;
    rendererRegistration = null;
}
```

If Rune_UI owns the layout and Rune_Husbandry owns the data, each plugin keeps
only its own registration. The command config composes them at page-open time.

## 3. Select the composition in the command config

```json
{
  "Parent": "RH_Command_Livestock",
  "UiRendererId": "runeteria:husbandry_ui",
  "UiContributors": [
    {
      "Id": "runeteria:husbandry",
      "Required": true
    }
  ]
}
```

Use `Required: true` when the custom page is not useful without that
contribution. Its absence causes standard-menu fallback. Use `false` for a
badge, indicator, or other feature that the renderer can omit.

## 4. Build one controller per open menu

The controller owns only rendering and event decoding:

```java
final class HusbandryCommandUiController
        implements CommandUiPageController<HusbandryUiEvent> {
    private static final String UI_PATH =
            "Rune_UI/HusbandryCommand.ui";

    @Override
    public BuilderCodec<HusbandryUiEvent> eventCodec() {
        return HusbandryUiEvent.CODEC;
    }

    @Override
    public void buildInitial(
            CommandUiOpenContext context,
            CommandUiSession session,
            CommandUiSnapshot snapshot,
            UICommandBuilder commands,
            UIEventBuilder events
    ) {
        commands.append(UI_PATH);
        renderAll(snapshot, commands, events);
    }

    @Override
    public void update(
            CommandUiUpdate update,
            UICommandBuilder commands,
            UIEventBuilder events
    ) {
        if (update.fullRefresh()) {
            renderAll(update.snapshot(), commands, events);
            return;
        }
        for (UUID rowId : update.changeSet().removedCompanionIds()) {
            removeCard(rowId, commands);
        }
        for (UUID rowId : update.changeSet().changedCompanionIds()) {
            CommandUiCompanionRow row =
                    update.snapshot().companionRow(rowId);
            if (row != null) renderCardIndicators(row, commands);
        }
    }

    @Override
    public void handleEvent(
            HusbandryUiEvent event,
            CommandUiSession session,
            CommandUiSnapshot snapshot
    ) {
        CommandUiActionRequest request = new CommandUiActionRequest(
                new CommandUiActionHandle(event.actionToken()),
                event.inputProvided() ? event.input() : null);
        session.invoke(request).thenAccept(this::renderActionResult);
    }
}
```

Put the UI file at `Common/UI/Custom/Rune_UI/HusbandryCommand.ui`. The string
passed to `append` starts below `Common/UI/Custom`; it must not repeat that
prefix.

Bind only `action.handle().token()` into the client event. Disabled actions
have no usable handle. Set input only for an action that declares optional or
required input.

## 5. Add namespaced presentation and actions

Create one contributor for each open session:

```java
final class HusbandryPresentationContributor
        implements CommandUiSessionContributor {
    private final CommandUiContributorId id;
    private final CommandUiContributorDirtySink dirty;

    HusbandryPresentationContributor(
            CommandUiContributorCreateContext context) {
        id = context.contributorId();
        dirty = context.dirtySink();
    }

    @Override
    public CommandUiContribution compose(
            CommandUiSnapshot base,
            CommandUiContribution previous,
            CommandUiDirtyScope scope
    ) {
        Map<UUID, Map<String, CommandUiValue>> rows = new LinkedHashMap<>();
        Map<UUID, Map<String, CommandUiContributorAction>> actions =
                new LinkedHashMap<>();
        for (CommandUiCompanionRow row : base.companionRows()) {
            rows.put(row.rowId(), Map.of(
                    "ready", CommandUiValue.of(isReady(row))));
            actions.put(row.rowId(), Map.of(
                    "toggle_ready", toggleReadyAction()));
        }
        return CommandUiContribution.withActions(
                id,
                Map.of("mode", CommandUiValue.of("husbandry")),
                rows,
                Map.of(), Map.of(), actions, Map.of());
    }

    private CommandUiContributorAction toggleReadyAction() {
        return new CommandUiContributorAction(
                "toggle_ready", "READY", "Toggle ready",
                CommandUiContributorAction.InputPolicy.NONE,
                false,
                context -> {
                    changeServerState(context.rowId());
                    dirty.markRowsDirty(Set.of(context.rowId()));
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                });
    }
}
```

The renderer reads this value from
`snapshot.contribution(CommandUiContributorId.of("runeteria:husbandry"))`.
The row UUID keeps a card indicator stable across updates. The action handler
runs behind Tamework's session, registration-generation, and world-thread
checks.

## 6. Open a custom flow

A page or row action can return a contributor-owned flow:

```java
return CompletableFuture.completedFuture(CommandUiActionResult.openFlow(
        new CommandUiCustomFlowView(
                UUID.randomUUID(),
                "runeteria:husbandry/checklist",
                contributorId,
                contributorGeneration,
                1L,
                1L,
                Map.of("step", CommandUiValue.of("overview")),
                Map.of(
                        contributorId.value() + "/next",
                        new CommandUiActionView(
                                "NEXT", "Continue", true,
                                null, false, null)))));
```

Define flow-scoped actions in the contribution. Tamework binds only the action
IDs requested by the flow view. Later flow actions can return `REPLACE`,
`UPDATE`, or `CLOSE`. Keep the flow instance ID stable until close. Increment
the revision when the step or data changes, and increment the action generation
when the action surface changes.

Built-in group and talent actions still return their built-in detached flow
types. A renderer can support built-in and contributor flows in the same page.

## 7. Render focused changes

`CommandUiUpdate.snapshot()` is always complete. Use the change set and each
contributor's dirty scope to avoid a full rebuild. For one indicator:

```java
private void renderReadyIndicator(
        UUID rowId,
        boolean ready,
        UICommandBuilder commands
) {
    String root = "#Companion_" + rowId;
    commands.set(root + " #Ready.Visible", ready);
}
```

The host submits the builder with `clear=false`, so untouched elements stay in
place.

## 8. Handle failures and confirmations

If an invocation returns `CONFIRMATION_REQUIRED`, show a confirmation control
and bind the new confirmation handle. Do not reuse the first handle.

Treat `STALE`, `DENIED`, `UNAVAILABLE`, `CONFLICT`, and `FAILED` as
server-authoritative failures. Request a fresh snapshot when the result asks
for it. Release controller and contributor listeners in `close()`.

Use `api.commandUi().diagnostics()` for safe registration, session, status,
timing, and fallback details. Diagnostics contain no action tokens, input, or
private contribution values.

## Related Pages

- [Command UI Renderer and Contributor API Reference](/mod/alecs-tamework/command-ui-provider-api-reference)
- [API Bootstrap and Capability Checks](/mod/alecs-tamework/api-bootstrap-and-capability-checks-recipe)
