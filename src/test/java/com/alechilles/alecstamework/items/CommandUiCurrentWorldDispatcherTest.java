package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deferred UI work must follow the player instead of an opening-world ref. */
class CommandUiCurrentWorldDispatcherTest {
    @Test
    void reResolvesDestinationWorldBeforeRunningDeferredWork() {
        QueueExecutor worldA = new QueueExecutor();
        QueueExecutor worldB = new QueueExecutor();
        AtomicReference<QueueExecutor> current = new AtomicReference<>(worldA);
        CommandUiCurrentWorldDispatcher dispatcher =
                new CommandUiCurrentWorldDispatcher(ignored ->
                        new CommandUiCurrentWorldDispatcher.ResolvedWorld(
                                null, null, current.get()));
        AtomicInteger runs = new AtomicInteger();

        assertTrue(dispatcher.dispatch(UUID.randomUUID(),
                (ref, store) -> runs.incrementAndGet()));
        current.set(worldB);
        worldA.runAll();

        assertEquals(0, runs.get());
        assertEquals(1, worldB.queued.size());
        worldB.runAll();
        assertEquals(1, runs.get());
    }

    private static final class QueueExecutor
            implements CommandUiCurrentWorldDispatcher.WorldExecutor {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public boolean execute(Runnable callback) {
            queued.add(callback);
            return true;
        }

        private void runAll() {
            List<Runnable> pending = List.copyOf(queued);
            queued.clear();
            pending.forEach(Runnable::run);
        }
    }
}
