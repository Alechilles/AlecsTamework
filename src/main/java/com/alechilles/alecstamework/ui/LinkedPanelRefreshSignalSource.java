package com.alechilles.alecstamework.ui;

import java.util.function.Consumer;

/**
 * Supplies scoped refresh signals to an open linked panel.
 */
@FunctionalInterface
public interface LinkedPanelRefreshSignalSource {

    /**
     * Returns a source that never publishes a signal.
     *
     * @return no-op source for pages without scoped refresh events
     */
    static LinkedPanelRefreshSignalSource none() {
        return listener -> () -> { };
    }

    /**
     * Subscribes to refresh signals for the current page scope.
     *
     * @param listener signal receiver
     * @return subscription that stops signal delivery when closed
     */
    AutoCloseable subscribe(Consumer<LinkedPanelRefreshSignal> listener);
}
