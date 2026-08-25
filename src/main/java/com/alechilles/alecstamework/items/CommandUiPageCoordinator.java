package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRegistry;
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
    @javax.annotation.Nullable
    private final CommandUiProviderRegistry legacyRegistry;
    @javax.annotation.Nullable
    private final CommandUiRegistry registry;
    private final CommandUiControllerResolver controllers;
    private final CommandUiSessionFactory sessions;

    CommandUiPageCoordinator(
            @Nonnull CommandUiProviderRegistry registry,
            @Nonnull CommandSelectionPageService actions
    ) {
        this.legacyRegistry = Objects.requireNonNull(registry, "registry");
        this.registry = null;
        this.controllers = new CommandUiControllerResolver(registry);
        this.sessions = new CommandUiSessionFactory(
                new CommandUiActionGateway(),
                Objects.requireNonNull(actions, "actions"));
    }

    CommandUiPageCoordinator(
            @Nonnull CommandUiRegistry registry,
            @Nonnull CommandSelectionPageService actions
    ) {
        this.legacyRegistry = null;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.controllers = new CommandUiControllerResolver(
                registry.rendererRegistry(), registry.contributorRegistry());
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
                List.of(), genericActions, bondedActions, snapshotFinalizer,
                () -> { },
                worldDispatcher, fallbackOpener);
    }

    @Nonnull
    Created create(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandUiOpenContext context,
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory,
            @Nonnull List<CommandUiContributorRequirement> contributorRequirements,
            @Nonnull List<CommandSelectionPageService.GenericUiActionBinding> genericActions,
            @Nonnull List<CommandSelectionPageService.BondedUiActionBinding> bondedActions,
            @Nonnull SnapshotFinalizer snapshotFinalizer,
            @Nonnull CommandUiHostPage.WorldDispatcher worldDispatcher,
            @Nonnull CommandUiHostPage.FallbackOpener fallbackOpener
    ) {
        return create(playerRef, context, baseSnapshot, standardFactory,
                contributorRequirements, genericActions, bondedActions,
                snapshotFinalizer, () -> { }, worldDispatcher, fallbackOpener);
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
        return create(playerRef, context, baseSnapshot, standardFactory,
                List.of(), genericActions, bondedActions, snapshotFinalizer,
                refreshRequest, worldDispatcher, fallbackOpener);
    }

    @Nonnull
    Created create(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandUiOpenContext context,
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory,
            @Nonnull List<CommandUiContributorRequirement> contributorRequirements,
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
        Objects.requireNonNull(contributorRequirements,
                "contributorRequirements");
        CommandUiControllerResolver.Resolved resolved = registry != null
                ? controllers.resolve(context.rendererId(),
                        contributorRequirements, context, standardFactory)
                : controllers.resolve(
                        context.providerId() == null ? null
                                : context.providerId().value(),
                        context, standardFactory);
        AtomicReference<CommandUiCompositionSession> compositionRef =
                new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean pendingCompositionRefresh =
                new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<CommandUiHostPage<?>> host = new AtomicReference<>();
        AtomicReference<CommandUiSessionImpl> sessionRef = new AtomicReference<>();
        Runnable compositionRefreshRequest = () -> {
            CommandUiCompositionSession current = compositionRef.get();
            if (current == null) {
                pendingCompositionRefresh.set(true);
                return;
            }
            try {
                boolean accepted = worldDispatcher.dispatch(
                        playerRef.getUuid(), new CommandUiHostPage.WorldOperation() {
                            @Override
                            public void run(
                                    Ref<EntityStore> ref,
                                    Store<EntityStore> store
                            ) {
                                current.refresh();
                            }

                            @Override
                            public void unavailable() {
                                // A stale executor cannot refresh the page.
                            }
                        });
                if (!accepted) pendingCompositionRefresh.set(true);
            } catch (RuntimeException | LinkageError ignored) {
                pendingCompositionRefresh.set(true);
            }
        };
        CommandUiCompositionSession composition = null;
        if (resolved.custom() && !resolved.contributors().isEmpty()) {
            composition = CommandUiCompositionSession.create(
                    baseSnapshot, context, resolved.contributors(),
                    (snapshot, changes) -> {
                        CommandUiSessionImpl current = sessionRef.get();
                        if (current == null || !current.isOpen()) return;
                        CommandUiSnapshot previous = current.snapshot();
                        if (!current.publishInternal(snapshot, changes)) return;
                        CommandUiHostPage<?> page = host.get();
                        if (page != null) {
                            page.applyUpdate(new com.alechilles.alecstamework.api.commandui.CommandUiUpdate(
                                    current.snapshot(), previous, changes));
                        }
                    }, compositionRefreshRequest);
            compositionRef.set(composition);
        }
        CommandUiSnapshot composedBase = composition == null
                ? baseSnapshot : composition.snapshot();
        CommandUiSessionFactory.CreatedSession createdSession = sessions.createMixed(
                composedBase.sessionId(), composedBase,
                resolved.rendererGeneration(),
                sessionDispatcher(playerRef.getUuid(), worldDispatcher),
                Objects.requireNonNull(refreshRequest, "refreshRequest"), reason -> {
                    CommandUiHostPage<?> current = host.get();
                    if (current != null) current.closeSession(reason);
                },
                (commands, events, clear) -> {
                    CommandUiHostPage<?> current = host.get();
                    return current != null && current.submitPartialUpdate(
                            commands, events, clear);
                },
                List.copyOf(genericActions), List.copyOf(bondedActions));
        sessionRef.set(createdSession.session());
        if (pendingCompositionRefresh.getAndSet(false)) {
            compositionRefreshRequest.run();
        }
        CommandUiSnapshot finalSnapshot = Objects.requireNonNull(
                snapshotFinalizer.finish(composedBase, createdSession.handles()),
                "final snapshot");
        if (composition != null) {
            finalSnapshot = finalSnapshot.withContributions(
                    composition.snapshot().contributions());
        }
        if (finalSnapshot != baseSnapshot) {
            createdSession.session().publishInternal(
                    finalSnapshot,
                    com.alechilles.alecstamework.api.commandui.CommandUiChangeSet.full());
        }
        CommandUiHostPage<?> page = createHost(
                playerRef, context, createdSession.session(), resolved,
                worldDispatcher, fallbackOpener);
        host.set(page);
        if (composition != null) page.own(composition);
        return new Created(page, createdSession.session(),
                createdSession.handles(), resolved.custom(),
                resolved.providerGeneration(), resolved.rendererGeneration(),
                composition);
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
        if (registry != null) {
            return new CommandUiHostPage(
                    playerRef, context, session,
                    (CommandUiPageController) resolved.controller(),
                    resolved.rendererId(), resolved.rendererGeneration(),
                    registry, worldDispatcher, fallbackOpener, null, true);
        }
        return new CommandUiHostPage(
                playerRef, context, session,
                (CommandUiPageController) resolved.controller(),
                resolved.providerId(), resolved.providerGeneration(),
                legacyRegistry, worldDispatcher, fallbackOpener, null);
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
            long providerGeneration,
            long rendererGeneration,
            @javax.annotation.Nullable CommandUiCompositionSession composition
    ) {
        Created {
            handles = List.copyOf(handles);
        }
    }
}
