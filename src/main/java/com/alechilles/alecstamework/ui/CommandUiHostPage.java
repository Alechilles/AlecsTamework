package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
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
    private final long providerGeneration;
    private final WorldDispatcher worldDispatcher;
    private final FallbackOpener fallbackOpener;
    private final UpdateEmitter updateEmitter;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AutoCloseable unregisterSubscription;

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
        this.providerGeneration = providerGeneration;
        this.worldDispatcher = Objects.requireNonNull(
                worldDispatcher, "worldDispatcher");
        this.fallbackOpener = Objects.requireNonNull(
                fallbackOpener, "fallbackOpener");
        this.updateEmitter = updateEmitter == null
                ? this::sendHostUpdate : updateEmitter;
        this.unregisterSubscription = subscribeProviderRemoval(providerRegistry);
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
            controller.buildInitial(context, session, session.snapshot(),
                    commandBuilder, eventBuilder);
        } catch (RuntimeException | LinkageError failure) {
            fail("initial build", failure, true);
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
            controller.handleEvent(event, session, session.snapshot(),
                    commands, events);
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
            return worldDispatcher.dispatch(playerUuid, (ref, store) -> {
                if (!open.get()) return;
                try {
                    UICommandBuilder commands = new UICommandBuilder();
                    UIEventBuilder events = new UIEventBuilder();
                    controller.update(update, commands, events);
                    updateEmitter.send(commands, events, false);
                } catch (RuntimeException | LinkageError failure) {
                    fail("update callback", failure, false);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            fail("update dispatch", failure, false);
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
            return worldDispatcher.dispatch(context.playerUuid(), (ref, store) -> {
                if (open.get()) updateEmitter.send(commands, events, false);
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

    public boolean isOpen() {
        return open.get();
    }

    private AutoCloseable subscribeProviderRemoval(
            @Nullable CommandUiProviderRegistry registry) {
        if (registry == null || providerId == null || providerGeneration <= 0L) {
            return () -> { };
        }
        return registry.subscribeUnregister((removedId, removedGeneration) -> {
            if (providerId.equals(removedId)
                    && providerGeneration == removedGeneration) {
                terminate(CommandUiCloseReason.PROVIDER_UNREGISTERED);
            }
        });
    }

    private void fail(String phase, Throwable failure, boolean openFallback) {
        if (!open.compareAndSet(true, false)) return;
        LOGGER.log(Level.SEVERE,
                "Command UI provider session failed during " + phase + ".",
                failure);
        closeResources(CommandUiCloseReason.FAILURE);
        if (!openFallback || context.playerUuid() == null) return;
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
        if (!open.compareAndSet(true, false)) return;
        UUID playerUuid = context.playerUuid();
        if (playerUuid == null) {
            closeResources(reason);
            return;
        }
        try {
            if (worldDispatcher.dispatch(playerUuid,
                    (ref, store) -> closeResources(reason))) return;
        } catch (RuntimeException | LinkageError ignored) {
            // Authority still must be invalidated if dispatch is unavailable.
        }
        closeResources(reason);
    }

    private void terminateHere(CommandUiCloseReason reason) {
        if (!open.compareAndSet(true, false)) return;
        closeResources(reason);
    }

    private void closeResources(CommandUiCloseReason reason) {
        try {
            unregisterSubscription.close();
        } catch (Exception ignored) {
            // Lifecycle cleanup continues for the controller and session.
        }
        try {
            controller.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Provider cleanup cannot keep action authority alive.
        }
        session.close(reason);
    }

    private void sendHostUpdate(
            UICommandBuilder commands,
            UIEventBuilder events,
            boolean clear
    ) {
        sendUpdate(commands, events, false);
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
