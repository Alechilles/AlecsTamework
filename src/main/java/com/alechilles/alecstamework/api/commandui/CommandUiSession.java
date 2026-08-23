package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-open-page command UI facade.
 *
 * <p>Reads return detached immutable values. The only gameplay mutation
 * request exposed to a provider is invocation of a Tamework-issued action
 * handle.</p>
 */
public interface CommandUiSession extends AutoCloseable {
    /** Stable random identity for this open page. */
    @Nonnull
    UUID sessionId();

    /** Returns the latest full immutable snapshot. */
    @Nonnull
    CommandUiSnapshot snapshot();

    /** Invokes one opaque handle after current authority revalidation. */
    @Nonnull
    CompletionStage<CommandUiActionResult> invoke(
            @Nullable CommandUiActionHandle handle
    );

    /** Decodes and routes an untrusted fixed event payload. */
    @Nonnull
    default CompletionStage<CommandUiActionResult> handleEvent(
            @Nullable CommandUiEvent event
    ) {
        if (event == null || event.actionToken() == null) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.denied("command UI event is invalid"));
        }
        return CompletableFuture.completedFuture(
                CommandUiActionResult.denied("command UI event is not handled"));
    }

    /** Requests a Tamework-owned refresh. Returns false after close. */
    boolean requestRefresh();

    /** Alias used by page controllers. */
    default boolean refresh() {
        return requestRefresh();
    }

    /** Returns the guarded update sink for this session. */
    @Nonnull
    default CommandUiUpdateSink updateSink() {
        return CommandUiUpdateSink.unavailable();
    }

    /** Returns whether the session is still able to receive callbacks. */
    default boolean isOpen() {
        return !isClosed();
    }

    default boolean isClosing() {
        return false;
    }

    default boolean isClosed() {
        return false;
    }

    /** Closes the session as a dismissal. */
    @Override
    default void close() {
        close(CommandUiCloseReason.DISMISSED);
    }

    /** Closes the session and its host once, then invalidates all issued handles. */
    void close(@Nonnull CommandUiCloseReason reason);

    /** Returns a stable failed result for a null or closed session. */
    @Nonnull
    static CompletionStage<CommandUiActionResult> closedResult() {
        return CompletableFuture.completedFuture(
                CommandUiActionResult.stale("command UI session is closed"));
    }

    /** Null-safe helper for adapters that receive an optional session. */
    static boolean sameSession(
            @Nullable CommandUiSession left,
            @Nullable CommandUiSession right
    ) {
        return left != null && right != null
                && Objects.equals(left.sessionId(), right.sessionId());
    }
}
