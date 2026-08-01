package com.alechilles.alecstamework.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules a linked-panel refresh callback after a millisecond delay.
 */
@FunctionalInterface
public interface LinkedPanelRefreshSignalSource {

    /**
     * Creates the production scheduler backed by CompletableFuture's delayed executor.
     *
     * @return the production delayed scheduler
     */
    static LinkedPanelRefreshSignalSource production() {
        return (delayMs, callback) -> CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(callback);
    }

    /**
     * Schedules a callback after the supplied delay.
     *
     * @param delayMs delay in milliseconds
     * @param callback work to run after the delay
     */
    void schedule(long delayMs, Runnable callback);
}
