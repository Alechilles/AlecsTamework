package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Internal Task 3 seam for constructing and binding one command UI session. */
final class CommandUiSessionFactory {
    private final CommandUiActionGateway gateway;
    private final CommandSelectionPageService actions;

    CommandUiSessionFactory(
            @Nonnull CommandUiActionGateway gateway,
            @Nonnull CommandSelectionPageService actions
    ) {
        this.gateway = gateway;
        this.actions = actions;
    }

    @Nonnull
    CreatedSession createGeneric(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot snapshot,
            long providerGeneration,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull List<CommandSelectionPageService.GenericUiActionBinding> visibleActions
    ) {
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, snapshot, gateway, dispatcher,
                providerGeneration, CommandUiSessionImpl.Mode.GENERIC,
                refresh, close, submitter);
        java.util.ArrayList<CommandUiActionHandle> handles = new java.util.ArrayList<>();
        for (CommandSelectionPageService.GenericUiActionBinding action : visibleActions) {
            handles.add(actions.bindGenericUiAction(session, action));
        }
        return new CreatedSession(session, List.copyOf(handles));
    }

    @Nonnull
    CreatedSession createBonded(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot snapshot,
            long providerGeneration,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull List<CommandSelectionPageService.BondedUiActionBinding> visibleActions
    ) {
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, snapshot, gateway, dispatcher,
                providerGeneration, CommandUiSessionImpl.Mode.BONDED,
                refresh, close, submitter);
        java.util.ArrayList<CommandUiActionHandle> handles = new java.util.ArrayList<>();
        for (CommandSelectionPageService.BondedUiActionBinding action : visibleActions) {
            CommandUiActionHandle handle = actions.bindBondedUiAction(session, action);
            if (handle != null) handles.add(handle);
        }
        return new CreatedSession(session, List.copyOf(handles));
    }

    /** Creates a menu that exposes separately authorized generic and bonded actions. */
    @Nonnull
    CreatedSession createMixed(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot snapshot,
            long providerGeneration,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull List<CommandSelectionPageService.GenericUiActionBinding> genericActions,
            @Nonnull List<CommandSelectionPageService.BondedUiActionBinding> bondedActions
    ) {
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, snapshot, gateway, dispatcher,
                providerGeneration, CommandUiSessionImpl.Mode.MIXED,
                refresh, close, submitter);
        java.util.ArrayList<CommandUiActionHandle> handles = new java.util.ArrayList<>();
        for (CommandSelectionPageService.GenericUiActionBinding action : genericActions) {
            handles.add(actions.bindGenericUiAction(session, action));
        }
        for (CommandSelectionPageService.BondedUiActionBinding action : bondedActions) {
            CommandUiActionHandle handle = actions.bindBondedUiAction(session, action);
            if (handle != null) handles.add(handle);
        }
        return new CreatedSession(session, List.copyOf(handles));
    }

    record CreatedSession(
            @Nonnull CommandUiSessionImpl session,
            @Nonnull List<CommandUiActionHandle> handles
    ) {
        CreatedSession {
            handles = List.copyOf(handles);
        }
    }
}
