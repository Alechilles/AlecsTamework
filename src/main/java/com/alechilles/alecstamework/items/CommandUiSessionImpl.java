package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiEvent;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdateSink;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Tamework-owned implementation of one command UI session. */
final class CommandUiSessionImpl implements CommandUiSession {
    enum Mode { GENERIC, BONDED }

    @FunctionalInterface
    interface PartialUpdateSubmitter {
        boolean submit(
                @Nonnull UICommandBuilder commandBuilder,
                @Nonnull UIEventBuilder eventBuilder,
                boolean clear
        );
    }

    private enum State { OPEN, CLOSING, CLOSED }

    private final UUID sessionId;
    private final long providerGeneration;
    private final Mode mode;
    private final CommandUiActionGateway actionGateway;
    private final CommandUiWorldDispatcher dispatcher;
    private final Runnable refreshRequest;
    private final Consumer<CommandUiCloseReason> closeCallback;
    private final PartialUpdateSubmitter partialUpdateSubmitter;
    private final AtomicReference<CommandUiSnapshot> currentSnapshot;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final CommandUiUpdateSink updateSink;

    CommandUiSessionImpl(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot initialSnapshot,
            @Nonnull CommandUiActionGateway actionGateway,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nonnull Mode mode
    ) {
        this(sessionId, initialSnapshot, actionGateway, dispatcher, mode,
                () -> { }, ignored -> { }, (commands, events, clear) -> false);
    }

    CommandUiSessionImpl(
            @Nonnull CommandUiSnapshot initialSnapshot,
            @Nonnull CommandUiActionGateway actionGateway,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nonnull Mode mode
    ) {
        this(initialSnapshot.sessionId(), initialSnapshot, actionGateway,
                dispatcher, mode);
    }

    CommandUiSessionImpl(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot initialSnapshot,
            @Nonnull CommandUiActionGateway actionGateway,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nonnull Mode mode,
            @Nullable Runnable refreshRequest,
            @Nullable Consumer<CommandUiCloseReason> closeCallback,
            @Nullable PartialUpdateSubmitter partialUpdateSubmitter
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.providerGeneration = 0L;
        CommandUiSnapshot snapshot = Objects.requireNonNull(
                initialSnapshot, "initialSnapshot");
        if (!sessionId.equals(snapshot.sessionId())) {
            throw new IllegalArgumentException("Session and snapshot IDs must match.");
        }
        this.actionGateway = Objects.requireNonNull(actionGateway, "actionGateway");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.refreshRequest = refreshRequest == null ? () -> { } : refreshRequest;
        this.closeCallback = closeCallback == null ? ignored -> { } : closeCallback;
        this.partialUpdateSubmitter = partialUpdateSubmitter == null
                ? (commands, events, clear) -> false : partialUpdateSubmitter;
        this.currentSnapshot = new AtomicReference<>(snapshot);
        this.actionGateway.openSession(sessionId, providerGeneration);
        this.updateSink = new CommandUiUpdateSink() {
            @Override
            public boolean requestRefresh() {
                return CommandUiSessionImpl.this.requestRefresh();
            }

            @Override
            public boolean submit(UICommandBuilder commandBuilder,
                                  UIEventBuilder eventBuilder,
                                  boolean clear) {
                return CommandUiSessionImpl.this.submitPartialUpdate(
                        commandBuilder, eventBuilder, clear);
            }

            @Override
            public boolean open() {
                return isOpen();
            }
        };
    }

    /** Creates a session bound to one provider registration generation. */
    CommandUiSessionImpl(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot initialSnapshot,
            @Nonnull CommandUiActionGateway actionGateway,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            long providerGeneration,
            @Nonnull Mode mode,
            @Nullable Runnable refreshRequest,
            @Nullable Consumer<CommandUiCloseReason> closeCallback,
            @Nullable PartialUpdateSubmitter partialUpdateSubmitter
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (providerGeneration < 0L) {
            throw new IllegalArgumentException("Provider generation cannot be negative.");
        }
        this.providerGeneration = providerGeneration;
        CommandUiSnapshot snapshot = Objects.requireNonNull(
                initialSnapshot, "initialSnapshot");
        if (!sessionId.equals(snapshot.sessionId())) {
            throw new IllegalArgumentException("Session and snapshot IDs must match.");
        }
        this.actionGateway = Objects.requireNonNull(actionGateway, "actionGateway");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.refreshRequest = refreshRequest == null ? () -> { } : refreshRequest;
        this.closeCallback = closeCallback == null ? ignored -> { } : closeCallback;
        this.partialUpdateSubmitter = partialUpdateSubmitter == null
                ? (commands, events, clear) -> false : partialUpdateSubmitter;
        this.currentSnapshot = new AtomicReference<>(snapshot);
        this.actionGateway.openSession(sessionId, providerGeneration);
        this.updateSink = new CommandUiUpdateSink() {
            @Override
            public boolean requestRefresh() {
                return CommandUiSessionImpl.this.requestRefresh();
            }

            @Override
            public boolean submit(UICommandBuilder commandBuilder,
                                  UIEventBuilder eventBuilder,
                                  boolean clear) {
                return CommandUiSessionImpl.this.submitPartialUpdate(
                        commandBuilder, eventBuilder, clear);
            }

            @Override
            public boolean open() {
                return isOpen();
            }
        };
    }

    @Nonnull
    @Override
    public UUID sessionId() {
        return sessionId;
    }

    long providerGeneration() {
        return providerGeneration;
    }

    Mode mode() {
        return mode;
    }

    @Nonnull
    @Override
    public CommandUiSnapshot snapshot() {
        return currentSnapshot.get();
    }

    @Nonnull
    @Override
    public CompletionStage<CommandUiActionResult> invoke(
            @Nullable CommandUiActionHandle handle
    ) {
        if (!isOpen()) return CommandUiSession.closedResult();
        try {
            CompletionStage<CompletionStage<CommandUiActionResult>> queued =
                    dispatcher.dispatch(() -> actionGateway.invoke(
                            sessionId, handle,
                            currentSnapshot.get().actionGeneration(),
                            mode == Mode.GENERIC
                                    ? CommandUiActionGateway.Route.GENERIC
                                    : CommandUiActionGateway.Route.BONDED));
            return flatten(queued);
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "command UI dispatch failed"));
        }
    }

    @Override
    public CompletionStage<CommandUiActionResult> handleEvent(
            @Nullable CommandUiEvent event
    ) {
        if (event == null || event.actionToken() == null) {
            return completed(CommandUiActionResult.denied(
                    "command UI event is invalid"));
        }
        try {
            return invoke(new CommandUiActionHandle(event.actionToken()));
        } catch (RuntimeException ignored) {
            return completed(CommandUiActionResult.denied(
                    "command UI event is invalid"));
        }
    }

    @Override
    public boolean requestRefresh() {
        if (!isOpen()) return false;
        try {
            dispatcher.dispatch(() -> {
                if (!isOpen()) return false;
                refreshRequest.run();
                return true;
            });
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private boolean submitPartialUpdate(
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            boolean clear
    ) {
        Objects.requireNonNull(commandBuilder, "commandBuilder");
        Objects.requireNonNull(eventBuilder, "eventBuilder");
        if (!isOpen()) return false;
        try {
            dispatcher.dispatch(() -> isOpen()
                    && partialUpdateSubmitter.submit(
                            commandBuilder, eventBuilder, clear));
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Publishes an authoritative snapshot from the internal host coordinator. */
    boolean publishInternal(@Nonnull CommandUiUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!isOpen()) return false;
        CommandUiSnapshot next = update.snapshot();
        if (!sessionId.equals(next.sessionId())) return false;
        CommandUiSnapshot current = currentSnapshot.get();
        long revision = Math.max(next.presentationRevision(),
                current.presentationRevision() + 1L);
        long generation = Math.max(next.actionGeneration(),
                current.actionGeneration());
        if (revision != next.presentationRevision()
                || generation != next.actionGeneration()) {
            next = next.withPresentationRevision(revision)
                    .withActionGeneration(generation);
        }
        if (generation != current.actionGeneration()) {
            actionGateway.advanceGeneration(sessionId, generation);
        }
        currentSnapshot.set(next);
        return true;
    }

    /** Convenience publication used only by the internal host coordinator. */
    boolean publishInternal(
            @Nonnull CommandUiSnapshot next,
            @Nonnull CommandUiChangeSet changeSet
    ) {
        return publishInternal(new CommandUiUpdate(next, currentSnapshot.get(), changeSet));
    }

    /** Replaces the current snapshot from an internal source. */
    boolean publishFromInternal(
            @Nonnull Supplier<CommandUiSnapshot> source,
            @Nonnull CommandUiChangeSet changeSet
    ) {
        if (!isOpen()) return false;
        try {
            CommandUiSnapshot next = source.get();
            return next != null && publishInternal(next, changeSet);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nonnull
    @Override
    public CommandUiUpdateSink updateSink() {
        return updateSink;
    }

    @Override
    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    @Override
    public boolean isClosing() {
        return state.get() == State.CLOSING;
    }

    @Override
    public boolean isClosed() {
        return state.get() == State.CLOSED;
    }

    @Override
    public void close(@Nonnull CommandUiCloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (!state.compareAndSet(State.OPEN, State.CLOSING)) return;
        try {
            CompletionStage<?> queued = dispatcher.dispatch(() -> {
                actionGateway.closeSession(sessionId);
                try {
                    closeCallback.accept(reason);
                } catch (RuntimeException | LinkageError ignored) {
                    // Provider cleanup must not leave the session half-closed.
                } finally {
                    state.set(State.CLOSED);
                }
                return null;
            });
            queued.whenComplete((ignored, failure) -> {
                if (failure != null) finishCloseAfterDispatchFailure();
            });
        } catch (RuntimeException | LinkageError ignored) {
            finishCloseAfterDispatchFailure();
        }
    }

    private void finishCloseAfterDispatchFailure() {
        actionGateway.closeSession(sessionId);
        state.set(State.CLOSED);
    }

    @Nonnull
    CommandUiActionHandle issue(
            @Nonnull CommandUiActionGateway.Route route,
            @Nonnull CommandUiAction action,
            @Nullable CommandUiActionGateway.AuthorityCheck authority,
            @Nonnull CommandUiActionGateway.ActionExecutor executor,
            boolean confirmationRequired
    ) {
        requireRoute(route);
        if (!isOpen()) {
            throw new IllegalStateException("Cannot issue an action for a closed session.");
        }
        return actionGateway.issue(sessionId, route, action,
                currentSnapshot.get().actionGeneration(), authority, executor,
                confirmationRequired);
    }

    @Nonnull
    CommandUiActionHandle issueGeneric(
            @Nonnull CommandUiAction action,
            @Nullable BooleanSupplier authority,
            @Nonnull Supplier<CompletionStage<CommandUiActionResult>> executor,
            boolean confirmationRequired
    ) {
        requireRoute(CommandUiActionGateway.Route.GENERIC);
        if (!isOpen()) {
            throw new IllegalStateException("Cannot issue an action for a closed session.");
        }
        return actionGateway.issueGeneric(sessionId, action,
                currentSnapshot.get().actionGeneration(), authority, executor,
                confirmationRequired);
    }

    @Nonnull
    CommandUiActionHandle issueBonded(
            @Nonnull CommandUiAction action,
            @Nullable BooleanSupplier authority,
            @Nonnull Supplier<CompletionStage<CommandUiActionResult>> executor,
            boolean confirmationRequired
    ) {
        requireRoute(CommandUiActionGateway.Route.BONDED);
        if (!isOpen()) {
            throw new IllegalStateException("Cannot issue an action for a closed session.");
        }
        return actionGateway.issueBonded(sessionId, action,
                currentSnapshot.get().actionGeneration(), authority, executor,
                confirmationRequired);
    }

    private void requireRoute(CommandUiActionGateway.Route route) {
        boolean generic = mode == Mode.GENERIC;
        if ((generic && route != CommandUiActionGateway.Route.GENERIC)
                || (!generic && route != CommandUiActionGateway.Route.BONDED)) {
            throw new IllegalArgumentException("Action route does not match session mode.");
        }
    }

    @Nonnull
    private static CompletionStage<CommandUiActionResult> flatten(
            @Nullable CompletionStage<CompletionStage<CommandUiActionResult>> queued
    ) {
        if (queued == null) {
            return completed(CommandUiActionResult.failed(
                    "command UI dispatch returned no result"));
        }
        return queued.handle((stage, failure) -> failure == null && stage != null
                ? stage : completed(CommandUiActionResult.failed(
                        "command UI dispatch failed")))
                .thenCompose(stage -> stage);
    }

    @Nonnull
    private static CompletionStage<CommandUiActionResult> completed(
            @Nonnull CommandUiActionResult result) {
        return CompletableFuture.completedFuture(result);
    }
}
