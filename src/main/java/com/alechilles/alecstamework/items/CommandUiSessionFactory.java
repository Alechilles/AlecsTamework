package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Store;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull CommandSelectionPageService.Actions callbacks,
            @Nonnull BooleanSupplier authority,
            @Nonnull Predicate<CommandUiAction> targetAvailable,
            @Nonnull List<CommandUiAction> visibleActions
    ) {
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, snapshot, gateway, dispatcher,
                0L, CommandUiSessionImpl.Mode.GENERIC, refresh, close, submitter);
        java.util.ArrayList<CommandUiActionHandle> handles = new java.util.ArrayList<>();
        for (CommandUiAction action : visibleActions) {
            handles.add(actions.bindGenericUiAction(session, action, callbacks,
                    authority, targetAvailable, action.confirmationRequired()));
        }
        return new CreatedSession(session, List.copyOf(handles));
    }

    @Nonnull
    CreatedSession createBonded(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot snapshot,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull UUID ownerUuid,
            @Nullable Ref<EntityStore> eventPlayerRef,
            @Nullable Store<EntityStore> eventStore,
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandPanelFeaturePresentation feature,
            @Nonnull CommandSelectionPageService.BondedLifecycleAuthority authority,
            @Nonnull List<CommandUiAction> visibleActions
    ) {
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, snapshot, gateway, dispatcher,
                0L, CommandUiSessionImpl.Mode.BONDED, refresh, close, submitter);
        java.util.ArrayList<CommandUiActionHandle> handles = new java.util.ArrayList<>();
        for (CommandUiAction action : visibleActions) {
            handles.add(actions.bindBondedUiAction(session, action, ownerUuid,
                    eventPlayerRef, eventStore, config, feature, authority,
                    action.confirmationRequired()));
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
