package com.alechilles.alecstamework.api.commandui;

/**
 * Public per-session controller returned by a {@link CommandUiProvider}.
 *
 * <p>Task 2 supplies the immutable session, snapshot, update, and event
 * contracts. The object parameters in this first API stage keep the
 * registration facade source-compatible while those types are completed.</p>
 *
 * @param <T> provider-owned event or UI payload type
 */
public interface CommandUiPageController<T> extends AutoCloseable {
    /** Builds the initial provider page state. */
    default void buildInitial(
            CommandUiOpenContext context,
            Object session,
            Object snapshot,
            T ui
    ) {
    }

    /** Applies a provider-local or Tamework partial update. */
    default void update(Object update, T ui) {
    }

    /** Handles an untrusted page event after Tamework has received it. */
    default void handleEvent(Object event, T ui) {
    }

    /** Releases provider-local state when the page closes. */
    @Override
    default void close() {
    }
}
