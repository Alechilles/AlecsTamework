package com.alechilles.alecstamework.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkedPanelRefreshCoordinatorTest {

    @Test
    void signalCarriesItsKind() {
        LinkedPanelRefreshSignal signal = new LinkedPanelRefreshSignal(LinkedPanelRefreshSignal.Kind.PROGRESSION);

        assertEquals(LinkedPanelRefreshSignal.Kind.PROGRESSION, signal.kind());
    }

    @Test
    void noOpSignalSourceSubscribesWithoutPublishingSignals() throws Exception {
        List<LinkedPanelRefreshSignal> signals = new ArrayList<>();

        AutoCloseable subscription = LinkedPanelRefreshSignalSource.none().subscribe(signals::add);
        subscription.close();

        assertEquals(List.of(), signals);
    }

    @Test
    void immediateSignalsCoalesceAtZeroDelay() {
        Fixture fixture = new Fixture();

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);

        assertEquals(List.of(0L), fixture.scheduler.delays());
        fixture.scheduler.runDue();
        assertEquals(1, fixture.permits.size());
    }

    @Test
    void oldProgressionRenderRefreshesImmediately() {
        Fixture fixture = new Fixture();
        fixture.coordinator.recordRendered(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        fixture.clock.set(5_000L);

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);

        assertEquals(List.of(0L), fixture.scheduler.delays());
        fixture.scheduler.runDue();
        assertEquals(List.of(true), progressionEligibility(fixture));
    }

    @Test
    void recentProgressionSignalsShareOneBoundaryWake() {
        Fixture fixture = new Fixture();
        fixture.coordinator.recordRendered(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        fixture.clock.set(1_000L);

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);
        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);

        assertEquals(List.of(4_000L), fixture.scheduler.delays());
        fixture.clock.set(5_000L);
        fixture.scheduler.runDue();
        assertEquals(1, fixture.permits.size());
    }

    @Test
    void progressionRenderResetsTheProgressionWindow() {
        Fixture fixture = new Fixture();
        fixture.coordinator.recordRendered(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        fixture.clock.set(4_000L);
        fixture.coordinator.recordRendered(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        fixture.clock.set(5_000L);

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);

        assertEquals(List.of(4_000L), fixture.scheduler.delays());
    }

    @Test
    void immediateLifecycleRefreshesInsideWindowCannotIncludeProgression() {
        Fixture fixture = new Fixture();
        fixture.coordinator.recordRendered(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        fixture.clock.set(1_000L);

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        fixture.scheduler.runDue();
        fixture.coordinator.recordRendered(false, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        fixture.clock.set(2_000L);
        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        fixture.scheduler.runDue();

        assertEquals(List.of(false, false), progressionEligibility(fixture));
    }

    @Test
    void simultaneousReasonsGrantOnlyOneProgressionPermit() {
        Fixture fixture = new Fixture();

        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        fixture.coordinator.recordRendered(false, 0L);
        fixture.scheduler.runDue();

        assertEquals(List.of(true, false), progressionEligibility(fixture));
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
    void visibleExpiredCountdownWakesImmediately() {
        Fixture fixture = new Fixture();

        fixture.coordinator.recordRendered(false, 0L);

        assertEquals(List.of(0L), fixture.scheduler.delays());
    }

    @Test
    void absentCountdownDoesNotScheduleAWake() {
        Fixture fixture = new Fixture();

        fixture.coordinator.recordRendered(false, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);

        assertEquals(List.of(), fixture.scheduler.delays());
    }

    @Test
    void safetyWakeRepeatsEveryThirtySeconds() {
        Fixture fixture = new Fixture();

        fixture.coordinator.start();
        assertEquals(List.of(30_000L), fixture.scheduler.delays());
        fixture.clock.set(30_000L);
        fixture.scheduler.runDue();

        assertEquals(1, fixture.permits.size());
        assertEquals(List.of(30_000L, 30_000L), fixture.scheduler.delays());
    }

    @Test
    void closeInvalidatesEveryQueuedReason() {
        Fixture fixture = new Fixture();
        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        fixture.coordinator.request(LinkedPanelRefreshSignal.Kind.PROGRESSION);
        fixture.coordinator.recordRendered(false, 1_000L);
        fixture.coordinator.start();

        fixture.coordinator.close();
        fixture.clock.set(30_000L);
        fixture.scheduler.runDue();

        assertEquals(List.of(), fixture.permits);
    }

    @Test
    void closeCannotReturnWhileAnAdmittedCallbackIsRunning() throws Exception {
        ManualClock clock = new ManualClock();
        QueuedScheduler scheduler = new QueuedScheduler(clock);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch closeAttempted = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        LinkedPanelRefreshCoordinator coordinator = new LinkedPanelRefreshCoordinator(
                clock,
                scheduler,
                permit -> {
                    callbackStarted.countDown();
                    await(releaseCallback);
                }
        );
        coordinator.request(LinkedPanelRefreshSignal.Kind.IMMEDIATE);

        Thread callbackThread = new Thread(scheduler::runDue);
        callbackThread.start();
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
        Thread closeThread = new Thread(() -> {
            closeAttempted.countDown();
            coordinator.close();
            closeReturned.countDown();
        });
        closeThread.start();
        assertTrue(closeAttempted.await(1, TimeUnit.SECONDS));
        assertEquals(1L, closeReturned.getCount());

        releaseCallback.countDown();
        callbackThread.join(1_000L);
        closeThread.join(1_000L);
        assertFalse(callbackThread.isAlive());
        assertFalse(closeThread.isAlive());
        assertEquals(0L, closeReturned.getCount());
    }

    private static List<Boolean> progressionEligibility(Fixture fixture) {
        return fixture.permits.stream()
                .map(LinkedPanelRefreshCoordinator.RenderPermit::progressionEligible)
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class Fixture {

        private final ManualClock clock = new ManualClock();
        private final QueuedScheduler scheduler = new QueuedScheduler(clock);
        private final List<LinkedPanelRefreshCoordinator.RenderPermit> permits = new ArrayList<>();
        private final LinkedPanelRefreshCoordinator coordinator = new LinkedPanelRefreshCoordinator(
                clock,
                scheduler,
                permits::add
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

    private static final class QueuedScheduler implements LinkedPanelRefreshCoordinator.DelayedScheduler {

        private final LongSupplier clock;
        private final List<ScheduledCallback> callbacks = new ArrayList<>();
        private final List<Long> scheduledDelays = new ArrayList<>();

        private QueuedScheduler(LongSupplier clock) {
            this.clock = clock;
        }

        @Override
        public void schedule(long delayMs, Runnable callback) {
            callbacks.add(new ScheduledCallback(clock.getAsLong() + delayMs, callback));
            scheduledDelays.add(delayMs);
        }

        List<Long> delays() {
            return scheduledDelays;
        }

        void runDue() {
            List<ScheduledCallback> due;
            synchronized (callbacks) {
                due = callbacks.stream()
                        .filter(callback -> callback.dueAtMs() <= clock.getAsLong())
                        .toList();
                callbacks.removeAll(due);
            }
            due.forEach(callback -> callback.callback().run());
        }
    }

    private record ScheduledCallback(long dueAtMs, Runnable callback) {
    }
}
