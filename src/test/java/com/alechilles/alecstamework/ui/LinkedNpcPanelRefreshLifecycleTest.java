package com.alechilles.alecstamework.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinkedNpcPanelRefreshLifecycleTest {

    @Test
    void initialRenderSeedsBoundaryWithoutSchedulingAnAutonomousHeartbeat() {
        ManualSignalSource source = new ManualSignalSource();
        ManualScheduler scheduler = new ManualScheduler();
        List<LinkedPanelRefreshCoordinator.RenderPermit> permits = new ArrayList<>();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(
                source, new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, permits::add));

        lifecycle.start(true, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);

        assertEquals(List.of(30_000L, 0L), scheduler.delays);
        assertEquals(1, source.subscriptions);
    }

    @Test
    void cachePublicationInInitialReadToSubscribeGapGetsAnImmediateCatchUpEvaluation() {
        InitialBuildGapSignalSource source = new InitialBuildGapSignalSource();
        ManualScheduler scheduler = new ManualScheduler();
        List<LinkedPanelRefreshCoordinator.RenderPermit> permits = new ArrayList<>();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(
                source, new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, permits::add));

        lifecycle.start(false, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        scheduler.runDelay(0L);

        assertTrue(source.readyPublicationCompleted);
        assertEquals(1, permits.size());
    }

    @Test
    void cacheFailureInInitialReadToSubscribeGapGetsAnImmediateCatchUpEvaluation() {
        InitialBuildGapSignalSource source = new InitialBuildGapSignalSource();
        ManualScheduler scheduler = new ManualScheduler();
        List<LinkedPanelRefreshCoordinator.RenderPermit> permits = new ArrayList<>();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(
                source, new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, permits::add));

        source.publishFailure = true;
        lifecycle.start(false, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        scheduler.runDelay(0L);

        assertTrue(source.failurePublicationCompleted);
        assertEquals(1, permits.size());
    }

    @Test
    void closeStopsSubscriptionAndCoordinatorBeforeQueuedSignalCanRender() throws Exception {
        ManualSignalSource source = new ManualSignalSource();
        ManualScheduler scheduler = new ManualScheduler();
        List<LinkedPanelRefreshCoordinator.RenderPermit> permits = new ArrayList<>();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(
                source, new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, permits::add));
        lifecycle.start(false, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        source.publish(LinkedPanelRefreshSignal.Kind.IMMEDIATE);

        lifecycle.close();
        scheduler.runAll();

        assertEquals(1, source.closes);
        assertEquals(List.of(), permits);
    }

    @Test
    void closeRacingBlockedSubscribeClosesTheCreatedSubscriptionOnceAndRejectsLaterSignals()
            throws Exception {
        BlockingSignalSource source = new BlockingSignalSource();
        ManualScheduler scheduler = new ManualScheduler();
        List<LinkedPanelRefreshCoordinator.RenderPermit> permits = new ArrayList<>();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(
                source, new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, permits::add));

        Thread starter = new Thread(() -> lifecycle.start(
                false, LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS));
        starter.start();
        assertTrue(source.subscribeEntered.await(1, TimeUnit.SECONDS));

        Thread closer = new Thread(lifecycle::close);
        closer.start();
        source.allowSubscribeToReturn.countDown();
        starter.join(1_000L);
        closer.join(1_000L);

        assertFalse(starter.isAlive());
        assertFalse(closer.isAlive());
        assertEquals(1, source.closes);
        source.publish(LinkedPanelRefreshSignal.Kind.IMMEDIATE);
        scheduler.runAll();
        assertEquals(List.of(), permits);
    }

    @Test
    void subscribeFailureClosesTheSeededCoordinator() {
        ManualScheduler scheduler = new ManualScheduler();
        LinkedNpcPanelRefreshLifecycle lifecycle = new LinkedNpcPanelRefreshLifecycle(
                listener -> { throw new LinkageError("missing signal dependency"); },
                new LinkedPanelRefreshCoordinator(() -> 0L, scheduler, ignored -> { }));

        assertThrows(LinkageError.class, () -> lifecycle.start(true,
                LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS));
        scheduler.runAll();

        assertEquals(List.of(), scheduler.callbacks);
    }

    private static final class ManualSignalSource implements LinkedPanelRefreshSignalSource {
        private Consumer<LinkedPanelRefreshSignal> listener;
        private int subscriptions;
        private int closes;

        @Override
        public AutoCloseable subscribe(Consumer<LinkedPanelRefreshSignal> listener) {
            this.listener = listener;
            subscriptions++;
            return () -> closes++;
        }

        private void publish(LinkedPanelRefreshSignal.Kind kind) {
            listener.accept(new LinkedPanelRefreshSignal(kind));
        }
    }

    private static final class ManualScheduler implements LinkedPanelRefreshCoordinator.DelayedScheduler {
        private final List<Long> delays = new ArrayList<>();
        private final List<Runnable> callbacks = new ArrayList<>();

        @Override
        public void schedule(long delayMs, Runnable callback) {
            delays.add(delayMs);
            callbacks.add(callback);
        }

        private void runAll() {
            List<Runnable> queued = new ArrayList<>(callbacks);
            callbacks.clear();
            queued.forEach(Runnable::run);
        }

        private void runDelay(long delay) {
            List<Runnable> queued = new ArrayList<>();
            for (int index = callbacks.size() - 1; index >= 0; index--) {
                if (delays.get(index) == delay) {
                    queued.add(callbacks.remove(index));
                    delays.remove(index);
                }
            }
            queued.forEach(Runnable::run);
        }
    }

    private static final class BlockingSignalSource implements LinkedPanelRefreshSignalSource {
        private final CountDownLatch subscribeEntered = new CountDownLatch(1);
        private final CountDownLatch allowSubscribeToReturn = new CountDownLatch(1);
        private Consumer<LinkedPanelRefreshSignal> listener;
        private int closes;

        @Override
        public AutoCloseable subscribe(Consumer<LinkedPanelRefreshSignal> listener) {
            this.listener = listener;
            subscribeEntered.countDown();
            try {
                allowSubscribeToReturn.await();
            } catch (InterruptedException exception) {
                throw new AssertionError(exception);
            }
            return () -> closes++;
        }

        private void publish(LinkedPanelRefreshSignal.Kind kind) {
            listener.accept(new LinkedPanelRefreshSignal(kind));
        }
    }

    /** Models cache completion after the page's initial REFRESHING read but before subscription. */
    private static final class InitialBuildGapSignalSource implements LinkedPanelRefreshSignalSource {
        private boolean publishFailure;
        private boolean readyPublicationCompleted;
        private boolean failurePublicationCompleted;

        @Override
        public AutoCloseable subscribe(Consumer<LinkedPanelRefreshSignal> listener) {
            if (publishFailure) {
                failurePublicationCompleted = true;
            } else {
                readyPublicationCompleted = true;
            }
            return () -> { };
        }
    }
}
