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

    @Test
    void disconnectCompletesQueuedWorkAsUnavailable() {
        QueueExecutor world = new QueueExecutor();
        AtomicReference<QueueExecutor> current = new AtomicReference<>(world);
        CommandUiCurrentWorldDispatcher dispatcher =
                new CommandUiCurrentWorldDispatcher(ignored -> {
                    QueueExecutor executor = current.get();
                    return executor == null ? null
                            : new CommandUiCurrentWorldDispatcher.ResolvedWorld(
                            null, null, executor);
                });
        AtomicInteger unavailable = new AtomicInteger();

        assertTrue(dispatcher.dispatch(UUID.randomUUID(),
                new com.alechilles.alecstamework.ui.CommandUiHostPage.WorldOperation() {
                    @Override public void run(
                            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,
                            com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store) {
                    }
                    @Override public void unavailable() {
                        unavailable.incrementAndGet();
                    }
                }));
        current.set(null);
        world.runAll();

        assertEquals(1, unavailable.get());
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
