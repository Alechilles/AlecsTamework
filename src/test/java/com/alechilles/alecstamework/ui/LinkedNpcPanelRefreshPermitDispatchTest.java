package com.alechilles.alecstamework.ui;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedNpcPanelRefreshPermitDispatchTest {

    @Test
    void rejectedDispatchCompletesTheExactPermitOnce() {
        LinkedPanelRefreshCoordinator.RenderPermit permit =
                new LinkedPanelRefreshCoordinator.RenderPermit(42L, true);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger completedPermitId = new AtomicInteger();

        LinkedNpcPanelRefreshPermitDispatch.dispatch(permit, ignored -> false,
                () -> { throw new AssertionError("Rejected work must not run."); },
                rejected -> {
                    completed.incrementAndGet();
                    completedPermitId.set((int) rejected.id());
                });

        assertEquals(1, completed.get());
        assertEquals(42, completedPermitId.get());
    }

    @Test
    void acceptedDispatchRunsWorkWithoutPrematurePermitCompletion() {
        LinkedPanelRefreshCoordinator.RenderPermit permit =
                new LinkedPanelRefreshCoordinator.RenderPermit(43L, false);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger workRuns = new AtomicInteger();

        LinkedNpcPanelRefreshPermitDispatch.dispatch(permit, task -> {
                    task.run();
                    return true;
                },
                workRuns::incrementAndGet, ignored -> completed.incrementAndGet());

        assertEquals(1, workRuns.get());
        assertEquals(0, completed.get());
    }
}
