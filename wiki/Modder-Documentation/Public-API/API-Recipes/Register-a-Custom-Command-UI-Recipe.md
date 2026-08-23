---
title: "Register a Custom Command UI Recipe"
order: 24
published: true
draft: false
---
# Register a Custom Command UI Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Public API](/mod/alecs-tamework/public-api)

Goal: let a Java plugin render a custom command-item menu while Tamework keeps
gameplay authority.

## 1. Register during plugin setup

Keep the registration handle as plugin state.

```java
private CommandUiProviderRegistration commandUiRegistration;

void registerCommandUi(TameworkApi api) {
    EnumSet<TameworkApiCapability> required = EnumSet.of(
            TameworkApiCapability.COMMAND_UI_PROVIDERS,
            TameworkApiCapability.COMMAND_UI_MANAGED_FLOWS);
    if (!api.getCapabilities().containsAll(required)) {
        return;
    }

    CommandUiProviderRegistrationResult result = api.commandUi().register(
            "runeteria:husbandry",
            HusbandryCommandPage::new
    );
    if (result.registered()) {
        commandUiRegistration = result.registration();
    }
}

void unregisterCommandUi() {
    if (commandUiRegistration != null) {
        commandUiRegistration.close();
        commandUiRegistration = null;
    }
}
```

Do not cache `commandUi().available()` as permanent server state. Check the
capability when the plugin registers.

## 2. Select the provider in the command config

```json
{
  "Parent": "Runeteria_Husbandry_Command_Base",
  "UiProviderId": "runeteria:husbandry"
}
```

The ID must match the registered ID after lowercase normalization.

## 3. Build one controller per open menu

This shortened example shows the important boundary. Your plugin owns the UI
asset and selectors. Tamework owns the session and actions.

```java
final class HusbandryCommandPage
        implements CommandUiPageController<HusbandryCommandPage.EventPayload> {
    private static final String UI_PATH =
            "Common/UI/Custom/RuneteriaHusbandryCommand.ui";

    private final CommandUiOpenContext context;

    HusbandryCommandPage(CommandUiOpenContext context) {
        this.context = context;
    }

    @Override
    public BuilderCodec<EventPayload> eventCodec() {
        return EventPayload.CODEC;
    }

    @Override
    public void buildInitial(
            CommandUiOpenContext ignored,
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
            CommandUiCompanionRow row = update.snapshot().companionRow(rowId);
            if (row != null) {
                renderCardIndicators(row, commands, events);
            }
        }
    }

    @Override
    public void handleEvent(
            EventPayload event,
            CommandUiSession session,
            CommandUiSnapshot snapshot
    ) {
        CommandUiActionHandle handle =
                new CommandUiActionHandle(event.actionToken);
        CommandUiActionRequest request = new CommandUiActionRequest(
                handle, event.inputProvided ? event.textInput : null);
        session.invoke(request).thenAccept(result -> {
            UICommandBuilder commands = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            renderResult(result, commands, events);
            session.updateSink().submit(commands, events, false);
        });
    }

    static final class EventPayload {
        static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(
                EventPayload.class, EventPayload::new)
                .<String>append(
                        new KeyedCodec<>("ActionToken", Codec.STRING),
                        (event, value) -> event.actionToken = value,
                        event -> event.actionToken)
                .add()
                .<String>append(
                        new KeyedCodec<>("TextInput", Codec.STRING),
                        (event, value) -> event.textInput = value,
                        event -> event.textInput)
                .add()
                .<Boolean>append(
                        new KeyedCodec<>("InputProvided", Codec.BOOLEAN),
                        (event, value) -> event.inputProvided = value,
                        event -> event.inputProvided)
                .add()
                .build();

        String actionToken;
        String textInput = "";
        boolean inputProvided;
    }
}
```

When you bind a button, put only `action.handle().token()` in its
`ActionToken` event value. Disabled actions have no usable handle.

Set `InputProvided` only for actions that accept text. Tamework rejects all
text, including an empty string, on a handle-only action.

## 4. Render small changes

`CommandUiUpdate.snapshot()` is always complete. Use its change set to avoid a
full page rebuild.

For example, a health or active-state change can update one card indicator:

```java
private void renderCardIndicators(
        CommandUiCompanionRow row,
        UICommandBuilder commands,
        UIEventBuilder events
) {
    String root = "#Companion_" + row.rowId();
    commands.set(root + " #Health.Text",
            row.currentHealth() + " / " + row.maxHealth());
    commands.set(root + " #Active.Visible", row.active());
}
```

Do not clear or rebuild the full list for this update. The host sends the
builder with `clear=false`.

## 5. Route confirmation actions

If an invocation returns `CONFIRMATION_REQUIRED`, show a provider-owned
confirmation overlay with the returned presentation. Bind the returned
confirmation handle to the confirm button. Do not reuse the first handle.
If the player cancels, discard the confirmation handle. The initiating action
stays available, so the player can start a new confirmation flow. Confirmation
handles expire after five seconds.

Treat `APPLIED` as a confirmed state change. Treat `ACCEPTED` as a successful
dispatch whose callback did not report whether it changed state. In both
cases, use the next Tamework snapshot as the source of visible state.

## 6. Render managed flows

Inspect `result.flowView()` after invocation. Keep this navigation local to the
provider page:

```java
CommandUiFlowView flow = result.flowView();
if (flow instanceof CommandUiGroupFlowView groups) {
    renderGroups(groups, commands, events);
} else if (flow instanceof CommandUiTalentFlowView talents) {
    renderTalents(talents, commands, events);
}
```

Use only the handles in the returned flow. Create and rename actions accept a
group-name value. Recolor accepts `#RRGGBB`. Purchase, selection, and ordinary
navigation actions accept no text. Reset and delete use the same confirmation
rule as main command actions.

Each managed mutation returns a fresh flow and retires the older managed
handles. Back navigation can show the retained main command snapshot. Main
snapshot refreshes do not invalidate the current managed flow.

## Notes

- Do not retain a `Player`, ECS reference, store, item stack, or mutable
  snapshot in the controller.
- Do not infer gameplay authority from visible text or metadata.
- Call `session.requestRefresh()` when provider-local flow needs a fresh
  Tamework snapshot.
- Release provider-local listeners in `close()`.
- A controller failure falls back to the standard Tamework menu.

## Related Pages

- [Command UI Provider API Reference](/mod/alecs-tamework/command-ui-provider-api-reference)
- [API Bootstrap and Capability Checks](/mod/alecs-tamework/api-bootstrap-and-capability-checks-recipe)
