package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiAction;
import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdateSink;
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

/**
 * Tamework-owned implementation of one command UI session.
 *
 * <p>Snapshot reads are lock-free and return immutable values. State changes,
 * action routing, and close callbacks are serialized by the host's world
 * thread; this class only rejects callbacks that arrive after lifecycle
 * closure.</p>
 */
final class CommandUiSessionImpl implements CommandUiSession {
    private enum State { OPEN, CLOSING, CLOSED }

    private final UUID sessionId;
    private final long providerGeneration;
    private final CommandUiActionGateway actionGateway;
    private final Runnable refreshRequest;
    private final Consumer<CommandUiCloseReason> closeCallback;
    private final AtomicReference<CommandUiSnapshot> currentSnapshot;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final CommandUiUpdateSink updateSink;

    CommandUiSessionImpl(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot initialSnapshot,
            @Nonnull CommandUiActionGateway actionGateway
    ) {
        this(sessionId, initialSnapshot, actionGateway, () -> { }, ignored -> { });
    }

    CommandUiSessionImpl(
            @Nonnull CommandUiSnapshot initialSnapshot,
            @Nonnull CommandUiActionGateway actionGateway
    ) {
        this(initialSnapshot.sessionId(), initialSnapshot, actionGateway);
    }

    CommandUiSessionImpl(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot initialSnapshot,
            @Nonnull CommandUiActionGateway actionGateway,
            @Nullable Runnable refreshRequest,
            @Nullable Consumer<CommandUiCloseReason> closeCallback
    ) {
        this(sessionId, initialSnapshot, actionGateway, 0L,
                refreshRequest, closeCallback);
    }

    /** Creates a session bound to one provider registration generation. */
    CommandUiSessionImpl(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot initialSnapshot,
            @Nonnull CommandUiActionGateway actionGateway,
            long providerGeneration,
            @Nullable Runnable refreshRequest,
            @Nullable Consumer<CommandUiCloseReason> closeCallback
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
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
        this.refreshRequest = refreshRequest == null ? () -> { } : refreshRequest;
        this.closeCallback = closeCallback == null ? ignored -> { } : closeCallback;
        this.currentSnapshot = new AtomicReference<>(snapshot);
        this.actionGateway.openSession(sessionId, providerGeneration);
        this.updateSink = new CommandUiUpdateSink() {
            @Override
            public boolean publish(CommandUiUpdate update) {
                return CommandUiSessionImpl.this.publish(update);
            }

            @Override
            public boolean requestRefresh() {
                return CommandUiSessionImpl.this.requestRefresh();
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
        if (!isOpen()) {
            return CommandUiSession.closedResult();
        }
        return actionGateway.invoke(sessionId, handle,
                currentSnapshot.get().actionGeneration());
    }

    @Override
    public boolean requestRefresh() {
        if (!isOpen()) return false;
        try {
            refreshRequest.run();
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Publishes a snapshot and computes no authority on behalf of the caller. */
    boolean publish(@Nonnull CommandUiUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!isOpen()) return false;
        CommandUiSnapshot next = update.snapshot();
        if (!sessionId.equals(next.sessionId())) return false;
        CommandUiSnapshot current = currentSnapshot.get();
        long revision = Math.max(next.presentationRevision(),
                current.presentationRevision() + 1L);
        long generation = Math.max(next.actionGeneration(), current.actionGeneration());
        if (revision != next.presentationRevision()
                || generation != next.actionGeneration()) {
            next = next.withPresentationRevision(revision)
                    .withActionGeneration(generation);
        }
        currentSnapshot.set(next);
        return true;
    }

    /** Convenience snapshot publication used by the host coordinator. */
    boolean publish(
            @Nonnull CommandUiSnapshot next,
            @Nonnull com.alechilles.alecstamework.api.commandui.CommandUiChangeSet changeSet
    ) {
        return publish(new CommandUiUpdate(next, currentSnapshot.get(), changeSet));
    }

    /** Replaces the current snapshot from a source and keeps revisions monotonic. */
    boolean publishFrom(@Nonnull Supplier<CommandUiSnapshot> source,
                        @Nonnull com.alechilles.alecstamework.api.commandui.CommandUiChangeSet changeSet) {
        if (!isOpen()) return false;
        try {
            CommandUiSnapshot next = source.get();
            return next != null && publish(next, changeSet);
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
        actionGateway.closeSession(sessionId);
        try {
            closeCallback.accept(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Provider cleanup must not leave the session half-closed.
        } finally {
            state.set(State.CLOSED);
        }
    }

    @Nonnull
    CommandUiActionHandle issue(
            @Nonnull CommandUiActionGateway.Route route,
            @Nonnull CommandUiAction action,
            @Nullable CommandUiActionGateway.AuthorityCheck authority,
            @Nonnull CommandUiActionGateway.ActionExecutor executor,
            boolean confirmationRequired
    ) {
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
        if (!isOpen()) {
            throw new IllegalStateException("Cannot issue an action for a closed session.");
        }
        return actionGateway.issueBonded(sessionId, action,
                currentSnapshot.get().actionGeneration(), authority, executor,
                confirmationRequired);
    }
}
