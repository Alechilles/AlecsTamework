package com.alechilles.alecstamework.ownership;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OwnerMutationWorldDispatcherTest {
    @Test
    void deadWorldRunsRejectedCallbackExactlyOnceEvenWhenItThrows() {
        AtomicInteger rejected = new AtomicInteger();

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
                }
        );

        assertEquals(1, rejected.get());
    }

    @Test
    void rejectedDispatchRunsTerminalCallbackExactlyOnce() {
        AtomicInteger rejected = new AtomicInteger();

        OwnerMutationWorldDispatcher.execute(
                () -> true,
                task -> {
                    throw new IllegalStateException("world shutting down");
                },
                () -> {
                    throw new AssertionError("rejected task must not run");
                },
                rejected::incrementAndGet
        );

        assertEquals(1, rejected.get());
    }

    @Test
    void acceptedDispatchDoesNotRunRejectedCallback() {
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        OwnerMutationWorldDispatcher.execute(
                () -> true,
                Runnable::run,
                tasks::incrementAndGet,
                rejected::incrementAndGet
        );

        assertEquals(1, tasks.get());
        assertEquals(0, rejected.get());
    }

    @Test
    void acceptedTaskFailureIsNotMisclassifiedAsDispatchRejection() {
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        OwnerMutationWorldDispatcher.execute(
                () -> true,
                Runnable::run,
                () -> {
                    tasks.incrementAndGet();
                    throw new IllegalStateException("task failure");
                },
                rejected::incrementAndGet
        );

        assertEquals(1, tasks.get());
        assertEquals(0, rejected.get());
    }
}
