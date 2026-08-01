package com.alechilles.alecstamework.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedPanelRefreshCoordinatorTest {

    @Test
    void immediateSignalsCoalesceAtZeroDelay() {
        Fixture fixture = new Fixture();

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);

        assertEquals(List.of(0L), fixture.scheduler.delays());
        fixture.scheduler.runDue();
        assertEquals(1, fixture.refreshes);
    }

    @Test
    void oldProgressionRenderRefreshesImmediately() {
        Fixture fixture = new Fixture();
        fixture.coordinator.recordRendered(true, 0L);
        fixture.clock.set(5_000L);

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);

        assertEquals(List.of(0L), fixture.scheduler.delays());
        fixture.scheduler.runDue();
        assertEquals(1, fixture.refreshes);
    }

    @Test
    void recentProgressionSignalsShareOneBoundaryWake() {
        Fixture fixture = new Fixture();
        fixture.coordinator.recordRendered(true, 0L);
        fixture.clock.set(1_000L);

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);
        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);

        assertEquals(List.of(4_000L), fixture.scheduler.delays());
        fixture.clock.set(5_000L);
        fixture.scheduler.runDue();
        assertEquals(1, fixture.refreshes);
    }

    @Test
    void progressionRenderResetsTheProgressionWindow() {
        Fixture fixture = new Fixture();
        fixture.coordinator.recordRendered(true, 0L);
        fixture.clock.set(4_000L);
        fixture.coordinator.recordRendered(true, 0L);
        fixture.clock.set(5_000L);

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);

        assertEquals(List.of(4_000L), fixture.scheduler.delays());
    }

    @Test
    void countdownAboveTenSecondsWakesInTenSeconds() {
        Fixture fixture = new Fixture();

        fixture.coordinator.recordRendered(false, 10_001L);

        assertEquals(List.of(10_000L), fixture.scheduler.delays());
    }

    @Test
    void countdownInFinalTenSecondsWakesInOneSecond() {
        Fixture fixture = new Fixture();

        fixture.coordinator.recordRendered(false, 10_000L);

        assertEquals(List.of(1_000L), fixture.scheduler.delays());
    }

    @Test
    void countdownBelowOneSecondWakesAtItsExactRemainingTime() {
        Fixture fixture = new Fixture();

        fixture.coordinator.recordRendered(false, 999L);

        assertEquals(List.of(999L), fixture.scheduler.delays());
    }

    @Test
    void safetyWakeRepeatsEveryThirtySeconds() {
        Fixture fixture = new Fixture();

        fixture.coordinator.start();
        assertEquals(List.of(30_000L), fixture.scheduler.delays());
        fixture.clock.set(30_000L);
        fixture.scheduler.runDue();

        assertEquals(1, fixture.refreshes);
        assertEquals(List.of(30_000L, 30_000L), fixture.scheduler.delays());
    }

    @Test
    void closeInvalidatesQueuedCallbacks() {
        Fixture fixture = new Fixture();
        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        fixture.coordinator.start();

        fixture.coordinator.close();
        fixture.clock.set(30_000L);
        fixture.scheduler.runDue();

        assertEquals(0, fixture.refreshes);
    }

    private static final class Fixture {

        private final ManualClock clock = new ManualClock();
        private final QueuedScheduler scheduler = new QueuedScheduler(clock);
        private int refreshes;
        private final LinkedPanelRefreshCoordinator coordinator = new LinkedPanelRefreshCoordinator(
                clock,
                scheduler,
                () -> refreshes++
        );
    }

    private static final class ManualClock implements LongSupplier {

        private long now;

        @Override
        public long getAsLong() {
            return now;
        }

        void set(long now) {
            this.now = now;
        }
    }

    private static final class QueuedScheduler implements LinkedPanelRefreshSignalSource {

        private final LongSupplier clock;
        private final List<ScheduledCallback> callbacks = new ArrayList<>();
        private final List<Long> scheduledDelays = new ArrayList<>();

        private QueuedScheduler(LongSupplier clock) {
            this.clock = clock;
        }

        @Override
        public void schedule(long delayMs, Runnable callback) {
            callbacks.add(new ScheduledCallback(clock.getAsLong() + delayMs, delayMs, callback));
            scheduledDelays.add(delayMs);
        }

        List<Long> delays() {
            return scheduledDelays;
        }

        void runDue() {
            List<ScheduledCallback> due = callbacks.stream()
                    .filter(callback -> callback.dueAtMs() <= clock.getAsLong())
                    .toList();
            callbacks.removeAll(due);
            due.forEach(callback -> callback.callback().run());
        }
    }

    private record ScheduledCallback(long dueAtMs, long delayMs, Runnable callback) {
    }
}
