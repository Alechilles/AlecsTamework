package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.alechilles.alecstamework.api.internal.CommandUiRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRendererRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tamework-owned page shell for one standard or plugin command UI controller.
 *
 * <p>The controller receives detached snapshots and guarded builders. The host
 * owns page lifetime, world dispatch, provider-generation removal, and
 * failure isolation.</p>
 */
public final class CommandUiHostPage<T> extends InteractiveCustomUIPage<T> {
    private static final Logger LOGGER = Logger.getLogger(
            CommandUiHostPage.class.getName());

    private final CommandUiOpenContext context;
    private final CommandUiSession session;
    private final CommandUiPageController<T> controller;
    private final CommandUiProviderId providerId;
    private final CommandUiRendererId rendererId;
    private final long providerGeneration;
    private final WorldDispatcher worldDispatcher;
    private final FallbackOpener fallbackOpener;
    private final UpdateEmitter updateEmitter;
    private final boolean customProvider;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private FallbackOwnership fallbackOwnership = FallbackOwnership.PRE_SHOW;
    private final SubscriptionSlot unregisterSubscription =
            new SubscriptionSlot();
    private final CopyOnWriteArrayList<AutoCloseable> ownedResources =
            new CopyOnWriteArrayList<>();

    public CommandUiHostPage(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandUiOpenContext context,
            @Nonnull CommandUiSession session,
            @Nonnull CommandUiPageController<T> controller,
            @Nullable CommandUiProviderId providerId,
            long providerGeneration,
            @Nullable CommandUiProviderRegistry providerRegistry,
            @Nonnull WorldDispatcher worldDispatcher,
            @Nonnull FallbackOpener fallbackOpener,
            @Nullable UpdateEmitter updateEmitter
    ) {
        super(Objects.requireNonNull(playerRef, "playerRef"),
                CustomPageLifetime.CanDismiss,
                Objects.requireNonNull(controller, "controller").eventCodec());
        if (providerGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Provider generation cannot be negative.");
        }
        this.context = Objects.requireNonNull(context, "context");
        this.session = Objects.requireNonNull(session, "session");
        this.controller = controller;
        this.providerId = providerId;
        this.rendererId = providerId == null
                ? null : CommandUiRendererId.tryParse(providerId.value()).orElse(null);
        this.providerGeneration = providerGeneration;
        this.worldDispatcher = Objects.requireNonNull(
                worldDispatcher, "worldDispatcher");
        this.fallbackOpener = Objects.requireNonNull(
                fallbackOpener, "fallbackOpener");
        this.updateEmitter = updateEmitter == null
                ? this::sendHostUpdate : updateEmitter;
        this.customProvider = providerId != null && providerGeneration > 0L;
        CommandUiProviderRegistry.ExactSubscription providerSubscription =
                subscribeProviderRemoval(providerRegistry);
        this.unregisterSubscription.set(providerSubscription.handle());
        if (!providerSubscription.active()) {
            terminate(CommandUiCloseReason.PROVIDER_UNREGISTERED, customProvider);
        }
    }

    /** Creates a host bound to one exact renderer registration generation. */
    public CommandUiHostPage(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandUiOpenContext context,
            @Nonnull CommandUiSession session,
            @Nonnull CommandUiPageController<T> controller,
            @Nullable CommandUiRendererId rendererId,
            long rendererGeneration,
            @Nullable CommandUiRegistry rendererRegistry,
            @Nonnull WorldDispatcher worldDispatcher,
            @Nonnull FallbackOpener fallbackOpener,
            @Nullable UpdateEmitter updateEmitter,
            boolean rendererMode
    ) {
        super(Objects.requireNonNull(playerRef, "playerRef"),
                CustomPageLifetime.CanDismiss,
                Objects.requireNonNull(controller, "controller").eventCodec());
        if (rendererGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Renderer generation cannot be negative.");
        }
        this.context = Objects.requireNonNull(context, "context");
        this.session = Objects.requireNonNull(session, "session");
        this.controller = controller;
        this.providerId = rendererId == null
                ? null : CommandUiProviderId.tryParse(rendererId.value()).orElse(null);
        this.rendererId = rendererId;
        this.providerGeneration = rendererGeneration;
        this.worldDispatcher = Objects.requireNonNull(
                worldDispatcher, "worldDispatcher");
        this.fallbackOpener = Objects.requireNonNull(
                fallbackOpener, "fallbackOpener");
        this.updateEmitter = updateEmitter == null
                ? this::sendHostUpdate : updateEmitter;
        this.customProvider = rendererId != null && rendererGeneration > 0L;
        CommandUiRendererRegistry.ExactSubscription rendererSubscription =
                subscribeRendererRemoval(rendererRegistry);
        this.unregisterSubscription.set(rendererSubscription.handle());
        if (!rendererSubscription.active()) {
            // The page owner handles fallback when construction returns a
            // closed host before the page can be shown.
            terminate(CommandUiCloseReason.PROVIDER_UNREGISTERED, false);
        }
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store
    ) {
        if (!open.get()) return;
        try {
            CommandUiHostController<T> contextual = contextualController();
            if (contextual == null) {
                controller.buildInitial(context, session, session.snapshot(),
                        commandBuilder, eventBuilder);
            } else {
                contextual.buildInitial(context, session, session.snapshot(),
                        ref, store, commandBuilder, eventBuilder);
            }
        } catch (RuntimeException | LinkageError failure) {
            fail("initial build", failure, customProvider);
        }
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nullable T event
    ) {
        if (!open.get()) return;
        try {
            UICommandBuilder commands = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            CommandUiHostController<T> contextual = contextualController();
            if (contextual == null) {
                controller.handleEvent(event, session, session.snapshot(),
                        commands, events);
            } else {
                contextual.handleEvent(event, session, session.snapshot(),
                        ref, store, commands, events);
            }
            updateEmitter.send(commands, events, false);
        } catch (RuntimeException | LinkageError failure) {
            fail("event callback", failure, false);
        }
    }

    /** Applies one Tamework-owned full snapshot and its change hints. */
    public boolean applyUpdate(@Nonnull CommandUiUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!open.get() || !session.sessionId().equals(
                update.snapshot().sessionId())) return false;
        UUID playerUuid = context.playerUuid();
        if (playerUuid == null) return false;
        try {
            return worldDispatcher.dispatch(playerUuid, new WorldOperation() {
                @Override
                public void run(Ref<EntityStore> ref, Store<EntityStore> store) {
                    if (!open.get()) return;
                    try {
                        UICommandBuilder commands = new UICommandBuilder();
                        UIEventBuilder events = new UIEventBuilder();
                        controller.update(update, commands, events);
                        updateEmitter.send(commands, events, false);
                    } catch (RuntimeException | LinkageError failure) {
                        fail("update callback", failure, customProvider);
                    }
                }

                @Override
                public void unavailable() {
                    // A stale executor cannot update a player page.
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            fail("update dispatch", failure, customProvider);
            return false;
        }
    }

    /** Accepts a provider-local update while forcing partial page semantics. */
    public boolean submitPartialUpdate(
            @Nonnull UICommandBuilder commands,
            @Nonnull UIEventBuilder events,
            boolean ignoredClear
    ) {
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(events, "events");
        if (!open.get() || context.playerUuid() == null) return false;
        try {
            return worldDispatcher.dispatch(context.playerUuid(), new WorldOperation() {
                @Override
                public void run(Ref<EntityStore> ref, Store<EntityStore> store) {
                    if (open.get()) updateEmitter.send(commands, events, false);
                }

                @Override
                public void unavailable() {
                    // A stale executor cannot update a player page.
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            fail("partial update", failure, false);
            return false;
        }
    }

    @Override
    public void onDismiss(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        terminateHere(CommandUiCloseReason.DISMISSED);
        super.onDismiss(ref, store);
    }

    /** Closes this host for a Tamework-owned lifecycle reason. */
    public void closeSession(@Nonnull CommandUiCloseReason reason) {
        terminate(Objects.requireNonNull(reason, "reason"));
    }

    /** Transfers fallback ownership to the page after construction succeeds. */
    public boolean takePageOwnership() {
        if (!customProvider) return open.get();
        synchronized (lifecycleLock) {
            if (!open.get() || fallbackOwnership != FallbackOwnership.PRE_SHOW) {
                return false;
            }
            fallbackOwnership = FallbackOwnership.PAGE;
            return true;
        }
    }

    /** Claims a pre-show fallback for the opener when the host already ended. */
    public boolean claimFallbackForOpener() {
        synchronized (lifecycleLock) {
            if (fallbackOwnership != FallbackOwnership.PRE_SHOW_FALLBACK) {
                return false;
            }
            fallbackOwnership = FallbackOwnership.OPENER_FALLBACK;
            return true;
        }
    }

    public boolean isOpen() {
        return open.get();
    }

    /** Gives the host cleanup ownership of one refresh or listener resource. */
    public boolean own(@Nonnull AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        if (!open.get()) {
            closeQuietly(resource);
            return false;
        }
        ownedResources.add(resource);
        if (open.get()) return true;
        if (ownedResources.remove(resource)) closeQuietly(resource);
        return false;
    }

    private CommandUiProviderRegistry.ExactSubscription subscribeProviderRemoval(
            @Nullable CommandUiProviderRegistry registry) {
        if (registry == null || providerId == null || providerGeneration <= 0L) {
            return new CommandUiProviderRegistry.ExactSubscription(
                    true, () -> { });
        }
        return registry.subscribeExactUnregister(providerId, providerGeneration,
                (removedId, removedGeneration) -> {
            if (providerId.equals(removedId)
                    && providerGeneration == removedGeneration) {
                terminate(CommandUiCloseReason.PROVIDER_UNREGISTERED,
                        customProvider);
            }
        });
    }

    private CommandUiRendererRegistry.ExactSubscription subscribeRendererRemoval(
            @Nullable CommandUiRegistry registry) {
        if (registry == null || rendererId == null || providerGeneration <= 0L) {
            return new CommandUiRendererRegistry.ExactSubscription(
                    true, () -> { });
        }
        return registry.rendererRegistry().subscribeExactUnregister(
                rendererId, providerGeneration,
                (removedId, removedGeneration) -> {
                    if (rendererId.equals(removedId)
                            && providerGeneration == removedGeneration) {
                        terminate(CommandUiCloseReason.PROVIDER_UNREGISTERED,
                                customProvider);
                    }
                });
    }

    private void fail(String phase, Throwable failure, boolean openFallback) {
        TerminationClaim termination = claimTermination(openFallback);
        if (!termination.claimed()) return;
        boolean hostFallback = termination.hostFallback();
        LOGGER.log(Level.SEVERE,
                "Command UI provider session failed during " + phase + ".",
                failure);
        closeResources(CommandUiCloseReason.FAILURE);
        if (!hostFallback || context.playerUuid() == null) return;
        try {
            boolean accepted = worldDispatcher.dispatch(
                    context.playerUuid(), (ref, store) ->
                            fallbackOpener.open(new CurrentWorld(ref, store)));
            if (!accepted) {
                LOGGER.warning("Command UI standard fallback dispatch was rejected.");
            }
        } catch (RuntimeException | LinkageError fallbackFailure) {
            LOGGER.log(Level.SEVERE,
                    "Command UI standard fallback failed.", fallbackFailure);
        }
    }

    private void terminate(CommandUiCloseReason reason) {
        terminate(reason, false);
    }

    private void terminate(CommandUiCloseReason reason, boolean openFallback) {
        TerminationClaim termination = claimTermination(openFallback);
        if (!termination.claimed()) return;
        boolean hostFallback = termination.hostFallback();
        UUID playerUuid = context.playerUuid();
        if (playerUuid == null) {
            closeResources(reason);
            return;
        }
        try {
            if (worldDispatcher.dispatch(playerUuid,
                    new WorldOperation() {
                        @Override
                        public void run(Ref<EntityStore> ref,
                                        Store<EntityStore> store) {
                            closeResources(reason);
                            if (hostFallback) {
                                try {
                                    fallbackOpener.open(new CurrentWorld(ref, store));
                                } catch (RuntimeException | LinkageError fallbackFailure) {
                                    LOGGER.log(Level.SEVERE,
                                            "Command UI standard fallback failed.",
                                            fallbackFailure);
                                }
                            }
                        }

                        @Override
                        public void unavailable() {
                            closeResources(reason);
                        }
                    })) return;
        } catch (RuntimeException | LinkageError ignored) {
            // Authority still must be invalidated if dispatch is unavailable.
        }
        closeResources(reason);
    }

    private TerminationClaim claimTermination(boolean openFallback) {
        synchronized (lifecycleLock) {
            if (!open.compareAndSet(true, false)) {
                return new TerminationClaim(false, false);
            }
            boolean hostFallback = claimHostFallback(openFallback);
            if (customProvider && !hostFallback) markPreShowFallbackRequired();
            return new TerminationClaim(true, hostFallback);
        }
    }

    private boolean claimHostFallback(boolean requested) {
        if (!requested || !customProvider
                || fallbackOwnership != FallbackOwnership.PAGE) {
            return false;
        }
        fallbackOwnership = FallbackOwnership.HOST_FALLBACK;
        return true;
    }

    private void markPreShowFallbackRequired() {
        if (fallbackOwnership == FallbackOwnership.PRE_SHOW) {
            fallbackOwnership = FallbackOwnership.PRE_SHOW_FALLBACK;
        }
    }

    private void terminateHere(CommandUiCloseReason reason) {
        synchronized (lifecycleLock) {
            if (!open.compareAndSet(true, false)) return;
        }
        closeResources(reason);
    }

    private void closeResources(CommandUiCloseReason reason) {
        if (!resourcesClosed.compareAndSet(false, true)) return;
        for (AutoCloseable resource : ownedResources) {
            if (ownedResources.remove(resource)) closeQuietly(resource);
        }
        unregisterSubscription.close();
        try {
            controller.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Provider cleanup cannot keep action authority alive.
        }
        session.close(reason);
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception | LinkageError ignored) {
            // Page cleanup continues for all other resources.
        }
    }

    private static final class SubscriptionSlot implements AutoCloseable {
        private AutoCloseable handle;
        private boolean closed;

        private void set(AutoCloseable handle) {
            AutoCloseable required = Objects.requireNonNull(handle, "handle");
            synchronized (this) {
                if (!closed) {
                    this.handle = required;
                    return;
                }
            }
            closeQuietly(required);
        }

        @Override
        public void close() {
            AutoCloseable current;
            synchronized (this) {
                if (closed) return;
                closed = true;
                current = handle;
                handle = null;
            }
            if (current != null) closeQuietly(current);
        }
    }

    private record TerminationClaim(boolean claimed, boolean hostFallback) {
    }

    private enum FallbackOwnership {
        PRE_SHOW,
        PAGE,
        PRE_SHOW_FALLBACK,
        OPENER_FALLBACK,
        HOST_FALLBACK
    }

    private void sendHostUpdate(
            UICommandBuilder commands,
            UIEventBuilder events,
            boolean clear
    ) {
        sendUpdate(commands, events, false);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private CommandUiHostController<T> contextualController() {
        return controller instanceof CommandUiHostController<?> contextual
                ? (CommandUiHostController<T>) contextual : null;
    }

    /** Resolves the current player world before one deferred host callback. */
    @FunctionalInterface
    public interface WorldDispatcher {
        boolean dispatch(@Nonnull UUID playerUuid,
                         @Nonnull WorldOperation operation);
    }

    @FunctionalInterface
    public interface WorldOperation {
        void run(@Nullable Ref<EntityStore> ref,
                 @Nullable Store<EntityStore> store);

        /** Completes deferred work when no current player world remains. */
        default void unavailable() {
            run(null, null);
        }
    }

    @FunctionalInterface
    public interface FallbackOpener {
        void open(@Nonnull CurrentWorld currentWorld);
    }

    @FunctionalInterface
    public interface UpdateEmitter {
        void send(@Nonnull UICommandBuilder commands,
                  @Nonnull UIEventBuilder events,
                  boolean clear);
    }

    /** Current resolved page context supplied only for the callback duration. */
    public record CurrentWorld(
            @Nullable Ref<EntityStore> playerRef,
            @Nullable Store<EntityStore> store
    ) {
    }
}
