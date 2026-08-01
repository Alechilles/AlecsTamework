package com.alechilles.alecstamework.ui;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Owns the linked-panel signal subscription and coordinator lifetime.
 */
final class LinkedNpcPanelRefreshLifecycle implements AutoCloseable {
    private final LinkedPanelRefreshSignalSource signalSource;
    private final LinkedPanelRefreshCoordinator coordinator;
    private AutoCloseable subscription;
    private boolean closed;

    LinkedNpcPanelRefreshLifecycle(
            @Nonnull LinkedPanelRefreshSignalSource signalSource,
            @Nonnull LinkedPanelRefreshCoordinator coordinator
    ) {
        this.signalSource = Objects.requireNonNull(signalSource, "signalSource");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    synchronized void start(boolean progressionIncluded, long shortestCountdownRemainingMs) {
        if (closed || subscription != null) return;
        coordinator.seedInitialRender(progressionIncluded, shortestCountdownRemainingMs);
        AutoCloseable created = signalSource.subscribe(this::onSignal);
        if (closed) {
            try { created.close(); } catch (Exception ignored) { }
            return;
        }
        subscription = created;
        coordinator.start();
    }

    synchronized void requestStateMutation() {
        if (closed) return;
        coordinator.rearmCountdownExpirationWake();
        coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
    }

    void recordRendered(LinkedPanelRefreshCoordinator.RenderPermit permit,
                        boolean progressionIncluded, long shortestCountdownRemainingMs) {
        coordinator.recordRendered(permit, progressionIncluded, shortestCountdownRemainingMs);
    }

    private synchronized void onSignal(LinkedPanelRefreshSignal signal) {
        if (closed || signal == null) return;
        if (signal.kind() == LinkedPanelRefreshSignal.Kind.IMMEDIATE) {
            coordinator.rearmCountdownExpirationWake();
        }
        coordinator.request(signal.kind());
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (subscription != null) subscription.close();
        } catch (Exception ignored) {
            // Closing a UI must not leave the coordinator alive because a source failed.
        } finally {
            coordinator.close();
        }
    }
}
