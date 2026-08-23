package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Constructs one controller, authoritative session, and guarded host page. */
final class CommandUiPageCoordinator {
    private final CommandUiProviderRegistry registry;
    private final CommandUiControllerResolver controllers;
    private final CommandUiSessionFactory sessions;

    CommandUiPageCoordinator(
            @Nonnull CommandUiProviderRegistry registry,
            @Nonnull CommandSelectionPageService actions
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.controllers = new CommandUiControllerResolver(registry);
        this.sessions = new CommandUiSessionFactory(
                new CommandUiActionGateway(),
                Objects.requireNonNull(actions, "actions"));
    }

    @Nonnull
    Created create(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandUiOpenContext context,
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory,
            @Nonnull List<CommandSelectionPageService.GenericUiActionBinding> genericActions,
            @Nonnull List<CommandSelectionPageService.BondedUiActionBinding> bondedActions,
            @Nonnull SnapshotFinalizer snapshotFinalizer,
            @Nonnull CommandUiHostPage.WorldDispatcher worldDispatcher,
            @Nonnull CommandUiHostPage.FallbackOpener fallbackOpener
    ) {
        return create(playerRef, context, baseSnapshot, standardFactory,
                genericActions, bondedActions, snapshotFinalizer, () -> { },
                worldDispatcher, fallbackOpener);
    }

    @Nonnull
    Created create(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandUiOpenContext context,
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory,
            @Nonnull List<CommandSelectionPageService.GenericUiActionBinding> genericActions,
            @Nonnull List<CommandSelectionPageService.BondedUiActionBinding> bondedActions,
            @Nonnull SnapshotFinalizer snapshotFinalizer,
            @Nonnull Runnable refreshRequest,
            @Nonnull CommandUiHostPage.WorldDispatcher worldDispatcher,
            @Nonnull CommandUiHostPage.FallbackOpener fallbackOpener
    ) {
        Objects.requireNonNull(playerRef, "playerRef");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(baseSnapshot, "baseSnapshot");
        Objects.requireNonNull(snapshotFinalizer, "snapshotFinalizer");
        CommandUiControllerResolver.Resolved resolved = controllers.resolve(
                context.providerId() == null ? null : context.providerId().value(),
                context, standardFactory);
        AtomicReference<CommandUiHostPage<?>> host = new AtomicReference<>();
        CommandUiSessionFactory.CreatedSession createdSession = sessions.createMixed(
                baseSnapshot.sessionId(), baseSnapshot,
                resolved.providerGeneration(),
                sessionDispatcher(playerRef.getUuid(), worldDispatcher),
                Objects.requireNonNull(refreshRequest, "refreshRequest"), ignored -> { },
                (commands, events, clear) -> {
                    CommandUiHostPage<?> current = host.get();
                    return current != null && current.submitPartialUpdate(
                            commands, events, clear);
                },
                List.copyOf(genericActions), List.copyOf(bondedActions));
        CommandUiSnapshot finalSnapshot = Objects.requireNonNull(
                snapshotFinalizer.finish(baseSnapshot, createdSession.handles()),
                "final snapshot");
        if (finalSnapshot != baseSnapshot) {
            createdSession.session().publishInternal(
                    finalSnapshot,
                    com.alechilles.alecstamework.api.commandui.CommandUiChangeSet.full());
        }
        CommandUiHostPage<?> page = createHost(
                playerRef, context, createdSession.session(), resolved,
                worldDispatcher, fallbackOpener);
        host.set(page);
        return new Created(page, createdSession.session(),
                createdSession.handles(), resolved.custom(),
                resolved.providerGeneration());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CommandUiHostPage<?> createHost(
            PlayerRef playerRef,
            CommandUiOpenContext context,
            CommandUiSessionImpl session,
            CommandUiControllerResolver.Resolved resolved,
            CommandUiHostPage.WorldDispatcher worldDispatcher,
            CommandUiHostPage.FallbackOpener fallbackOpener
    ) {
        return new CommandUiHostPage(
                playerRef, context, session,
                (CommandUiPageController) resolved.controller(),
                resolved.providerId(), resolved.providerGeneration(),
                resolved.custom() ? registry : null,
                worldDispatcher, fallbackOpener, null);
    }

    private static CommandUiWorldDispatcher sessionDispatcher(
            UUID playerUuid,
            CommandUiHostPage.WorldDispatcher dispatcher
    ) {
        return new CommandUiWorldDispatcher() {
            @Override
            public <T> CompletionStage<T> dispatch(Supplier<T> operation) {
                CompletableFuture<T> result = new CompletableFuture<>();
                try {
                    boolean accepted = dispatcher.dispatch(playerUuid,
                            new CommandUiHostPage.WorldOperation() {
                                @Override
                                public void run(
                                        Ref<EntityStore> ref,
                                        Store<EntityStore> store
                                ) {
                                    try {
                                        result.complete(operation.get());
                                    } catch (RuntimeException | LinkageError failure) {
                                        result.completeExceptionally(failure);
                                    }
                                }

                                @Override
                                public void unavailable() {
                                    result.completeExceptionally(
                                            new IllegalStateException(
                                                    "Current command UI world is unavailable."));
                                }
                            });
                    if (!accepted) {
                        result.completeExceptionally(new IllegalStateException(
                                "Current command UI world is unavailable."));
                    }
                } catch (RuntimeException | LinkageError failure) {
                    result.completeExceptionally(failure);
                }
                return result;
            }
        };
    }

    @FunctionalInterface
    interface SnapshotFinalizer {
        CommandUiSnapshot finish(CommandUiSnapshot base,
                                 List<CommandUiActionHandle> handles);
    }

    record Created(
            CommandUiHostPage<?> host,
            CommandUiSessionImpl session,
            List<CommandUiActionHandle> handles,
            boolean custom,
            long providerGeneration
    ) {
        Created {
            handles = List.copyOf(handles);
        }
    }
}
