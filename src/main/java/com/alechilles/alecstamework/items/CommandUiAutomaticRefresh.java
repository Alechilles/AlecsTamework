package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedPanelRefreshCoordinator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Gives custom command pages the standard page's periodic presentation refresh. */
final class CommandUiAutomaticRefresh implements AutoCloseable {
    private final CommandUiSessionImpl session;
    private final LinkedPanelRefreshCoordinator coordinator;
    private final AtomicBoolean closed = new AtomicBoolean();

    CommandUiAutomaticRefresh(CommandUiSessionImpl session) {
        this(session, System::currentTimeMillis,
                LinkedPanelRefreshCoordinator.DelayedScheduler.production());
    }

    CommandUiAutomaticRefresh(
            CommandUiSessionImpl session,
            LongSupplier clock,
            LinkedPanelRefreshCoordinator.DelayedScheduler scheduler
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.coordinator = new LinkedPanelRefreshCoordinator(clock, scheduler,
                this::refresh);
    }

    void start() {
        coordinator.start(true);
    }

    private void refresh(LinkedPanelRefreshCoordinator.RenderPermit permit) {
        if (closed.get()) return;
        session.requestRefresh();
        coordinator.recordRendered(permit, permit.progressionEligible(),
                LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        coordinator.close();
    }
}
