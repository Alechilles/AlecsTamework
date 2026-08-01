package com.alechilles.alecstamework.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Observable lifecycle coverage used by page close and replacement navigation. */
class TameworkCommandSelectionPageNavigationTest {

    @Test
    void replacementNavigationClosesThePanelRefreshSubscription() {
        SignalSource source = new SignalSource();
        Scheduler scheduler = new Scheduler();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(source,
                new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, ignored -> { }));
        lifecycle.start(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);

        lifecycle.close();
        source.publish(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        scheduler.runAll();

        assertEquals(1, source.closed);
        assertEquals(List.of(30_000L), scheduler.delays);
    }

    @Test
    void idlePanelHasNoOneSecondRefreshUntilASignalOrCountdownIsDue() {
        SignalSource source = new SignalSource();
        Scheduler scheduler = new Scheduler();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(source,
                new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, ignored -> { }));

        lifecycle.start(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);

        assertEquals(List.of(30_000L), scheduler.delays);
    }

    @Test
    void immediateSignalSchedulesAnUrgentRefreshWithoutARecurringLoop() {
        SignalSource source = new SignalSource();
        Scheduler scheduler = new Scheduler();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(source,
                new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, ignored -> { }));
        lifecycle.start(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);

        source.publish(LinkedPanelRefreshSignal.Kind.IMMEDIATE);

        assertEquals(List.of(30_000L, 0L), scheduler.delays);
    }

    private static final class SignalSource implements LinkedPanelRefreshSignalSource {
        private Consumer<LinkedPanelRefreshSignal> listener;
        private int closed;

        @Override public AutoCloseable subscribe(Consumer<LinkedPanelRefreshSignal> listener) {
            this.listener = listener;
            return () -> closed++;
        }

        private void publish(LinkedPanelRefreshSignal.Kind kind) {
            listener.accept(new LinkedPanelRefreshSignal(kind));
        }
    }

    private static final class Scheduler implements LinkedPanelRefreshCoordinator.DelayedScheduler {
        private final List<Long> delays = new ArrayList<>();
        private final List<Runnable> callbacks = new ArrayList<>();

        @Override public void schedule(long delayMs, Runnable callback) {
            delays.add(delayMs);
            callbacks.add(callback);
        }

        private void runAll() {
            List<Runnable> pending = new ArrayList<>(callbacks);
            callbacks.clear();
            pending.forEach(Runnable::run);
        }
    }
}
