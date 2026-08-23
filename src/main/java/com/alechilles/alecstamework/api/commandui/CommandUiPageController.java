package com.alechilles.alecstamework.api.commandui;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import javax.annotation.Nonnull;

/**
 * Public per-session controller returned by a {@link CommandUiProvider}.
 *
 * <p>Task 2 supplies the immutable session, snapshot, update, and event
 * contracts. The object parameters in this first API stage keep the
 * registration facade source-compatible while those records are completed.
 * {@code T} is the provider event payload. The event codec is part of this
 * contract so a host can decode untrusted page events before dispatch.</p>
 *
 * @param <T> provider-owned event payload type
 */
public interface CommandUiPageController<T> extends AutoCloseable {
    /** Returns the codec used by the host to decode provider page events. */
    @Nonnull
    BuilderCodec<T> eventCodec();

    /** Alias for integrations that call the event codec simply {@code codec}. */
    @Nonnull
    default BuilderCodec<T> codec() {
        return eventCodec();
    }

    /** Builds the initial provider page state. */
    default void buildInitial(
            CommandUiOpenContext context,
            Object session,
            Object snapshot,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder
    ) {
    }

    /** Applies a provider-local or Tamework partial update. */
    default void update(
            Object update,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder
    ) {
    }

    /** Handles an untrusted page event after Tamework has received it. */
    default void handleEvent(T event, Object session, Object snapshot) {
    }

    /** Handles an event and emits any immediate page update. */
    default void handleEvent(
            T event,
            Object session,
            Object snapshot,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder
    ) {
        handleEvent(event, session, snapshot);
    }

    /** Releases provider-local state when the page closes. */
    @Override
    default void close() {
    }
}
