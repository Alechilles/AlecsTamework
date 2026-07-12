package com.alechilles.alecstamework.runtime.dispatch;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Regression coverage for shutdown between world dispatch acceptance and task start. */
class LeaseBoundWorldDispatcherTest {
    @Test
    void deadWorldRunsRejectedCallbackExactlyOnceEvenWhenItThrows() {
        AtomicInteger rejected = new AtomicInteger();
        ManualExecutor timeout = new ManualExecutor();

        LeaseBoundWorldDispatcher.execute(
                () -> false,
                task -> {
                    throw new AssertionError("dead worlds must not dispatch");
                },
                () -> {
                    throw new AssertionError("dead worlds must not run the task");
                },
                () -> {
                    rejected.incrementAndGet();
                    throw new IllegalStateException("callback failure");
                },
                timeout
        );

        assertEquals(1, rejected.get());
        assertFalse(timeout.hasTask());
    }

    @Test
    void rejectedDispatchRunsTerminalCallbackExactlyOnce() {
        AtomicInteger rejected = new AtomicInteger();
        ManualExecutor timeout = new ManualExecutor();

        LeaseBoundWorldDispatcher.execute(
                () -> true,
                task -> {
                    throw new IllegalStateException("world shutting down");
                },
                () -> {
                    throw new AssertionError("rejected task must not run");
                },
                rejected::incrementAndGet,
                timeout
        );

        timeout.runNext();
        assertEquals(1, rejected.get());
    }

    @Test
    void acceptedDispatchDoesNotRunRejectedCallback() {
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ManualExecutor timeout = new ManualExecutor();

        LeaseBoundWorldDispatcher.execute(
                () -> true,
                Runnable::run,
                tasks::incrementAndGet,
                rejected::incrementAndGet,
                timeout
        );

        timeout.runNext();
        assertEquals(1, tasks.get());
        assertEquals(0, rejected.get());
    }

    @Test
    void acceptedTaskFailureIsNotMisclassifiedAsDispatchRejection() {
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ManualExecutor timeout = new ManualExecutor();

        LeaseBoundWorldDispatcher.execute(
                () -> true,
                Runnable::run,
                () -> {
                    tasks.incrementAndGet();
                    throw new IllegalStateException("task failure");
                },
                rejected::incrementAndGet,
                timeout
        );

        timeout.runNext();
        assertEquals(1, tasks.get());
        assertEquals(0, rejected.get());
    }

    @Test
    void acceptedButNeverStartedDispatchTimesOutExactlyOnce() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ManualExecutor timeout = new ManualExecutor();

        LeaseBoundWorldDispatcher.execute(
                () -> true,
                queued::set,
                tasks::incrementAndGet,
                rejected::incrementAndGet,
                timeout
        );

        assertNotNull(queued.get());
        assertEquals(0, rejected.get());
        timeout.runNext();
        assertEquals(1, rejected.get());
        assertEquals(0, tasks.get());
    }

    @Test
    void queuedWrapperBecomesNoOpAfterStartTimeout() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ManualExecutor timeout = new ManualExecutor();

        LeaseBoundWorldDispatcher.execute(
                () -> true,
                queued::set,
                tasks::incrementAndGet,
                rejected::incrementAndGet,
                timeout
        );

        timeout.runNext();
        queued.get().run();
        assertEquals(1, rejected.get());
        assertEquals(0, tasks.get());
    }

    @Test
    void timeoutRejectionRunsOnWatchdogThread() throws InterruptedException {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<String> rejectionThread = new AtomicReference<>();
        ManualExecutor timeout = new ManualExecutor();

        LeaseBoundWorldDispatcher.execute(
                () -> true,
                queued::set,
                () -> {
                    throw new AssertionError("timed-out task must not run");
                },
                () -> rejectionThread.set(Thread.currentThread().getName()),
                timeout
        );

        Thread watchdog = new Thread(timeout::runNext, "lease-watchdog-test");
        watchdog.start();
        watchdog.join();

        assertEquals("lease-watchdog-test", rejectionThread.get());
        queued.get().run();
    }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final AtomicReference<Runnable> task = new AtomicReference<>();

        @Override
        public void execute(Runnable command) {
            if (!task.compareAndSet(null, command)) {
                throw new IllegalStateException("timeout already scheduled");
            }
        }

        private boolean hasTask() {
            return task.get() != null;
        }

        private void runNext() {
            Runnable current = task.getAndSet(null);
            assertNotNull(current);
            current.run();
        }
    }
}
