package com.alechilles.alecstamework.ownership;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OwnerMutationWorldDispatcherTest {
    @Test
    void deadWorldRunsRejectedCallbackExactlyOnceEvenWhenItThrows() {
        AtomicInteger rejected = new AtomicInteger();
        ManualExecutor timeout = new ManualExecutor();

        OwnerMutationWorldDispatcher.execute(
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

        OwnerMutationWorldDispatcher.execute(
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

        OwnerMutationWorldDispatcher.execute(
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

        OwnerMutationWorldDispatcher.execute(
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

        OwnerMutationWorldDispatcher.execute(
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

        OwnerMutationWorldDispatcher.execute(
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
