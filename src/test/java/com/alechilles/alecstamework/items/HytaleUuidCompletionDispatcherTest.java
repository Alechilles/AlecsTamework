package com.alechilles.alecstamework.items.persistence;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import sun.misc.Unsafe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Completion scheduling retains stable UUIDs, never live Hytale actor state. */
class HytaleUuidCompletionDispatcherTest {
    private static final UUID ACTOR = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );

    @Test
    void unavailableWorldDoesNotRunOrAcceptCompletion() {
        AtomicInteger completions = new AtomicInteger();
        HytaleUuidCompletionDispatcher dispatcher =
                new HytaleUuidCompletionDispatcher(ignored -> null);

        boolean accepted = dispatcher.dispatch(
                "world",
                ACTOR,
                (world, store, actorRef, actor) ->
                        completions.incrementAndGet()
        );

        assertFalse(accepted);
        assertTrue(completions.get() == 0);
    }

    @Test
    void invalidInputDoesNotConsultWorldLookup() {
        AtomicInteger lookups = new AtomicInteger();
        HytaleUuidCompletionDispatcher dispatcher =
                new HytaleUuidCompletionDispatcher(worldKey -> {
                    lookups.incrementAndGet();
                    return null;
                });

        assertFalse(dispatcher.dispatch(
                " ", ACTOR, (world, store, actorRef, actor) -> { }
        ));
        assertFalse(dispatcher.dispatch(
                "world", null, (world, store, actorRef, actor) -> { }
        ));
        assertFalse(dispatcher.dispatch("world", ACTOR, null));
        assertTrue(lookups.get() == 0);
    }

    @Test
    void lookupFailureIsContainedAsUnscheduledCompletion() {
        HytaleUuidCompletionDispatcher dispatcher =
                new HytaleUuidCompletionDispatcher(worldKey -> {
                    throw new IllegalStateException("universe unavailable");
                });

        assertFalse(dispatcher.dispatch(
                "world",
                ACTOR,
                (world, store, actorRef, actor) -> { }
        ));
    }

    @Test
    void dispatcherStoresNoLiveActorOrEcsState() {
        for (Field field
                : HytaleUuidCompletionDispatcher.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertFalse(
                    type == Player.class
                            || Ref.class.isAssignableFrom(type)
                            || Store.class.isAssignableFrom(type),
                    field.toString()
            );
        }
    }

    @Test
    void nullLookupIsRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new HytaleUuidCompletionDispatcher(
                        (Function<String, World>) null
                )
        );
    }

    @Test
    void replacedWorldSuppressesResolutionAndCompletion() {
        WorldFixture fixture = new WorldFixture();
        fixture.current.set(fixture.scheduled);
        fixture.afterSchedule = () -> fixture.current.set(fixture.replacement);
        HytaleUuidCompletionDispatcher dispatcher =
                new HytaleUuidCompletionDispatcher(fixture);

        assertTrue(dispatcher.dispatch(
                "world",
                ACTOR,
                (world, store, actorRef, actor) ->
                        fixture.completions.incrementAndGet()
        ));

        assertTrue(fixture.resolutions.get() == 0);
        assertTrue(fixture.completions.get() == 0);
    }

    @Test
    void movedOrDisconnectedActorSuppressesCompletion() {
        WorldFixture fixture = new WorldFixture();
        fixture.current.set(fixture.scheduled);
        HytaleUuidCompletionDispatcher dispatcher =
                new HytaleUuidCompletionDispatcher(fixture);

        assertTrue(dispatcher.dispatch(
                "world",
                ACTOR,
                (world, store, actorRef, actor) ->
                        fixture.completions.incrementAndGet()
        ));

        assertTrue(fixture.resolutions.get() == 1);
        assertTrue(fixture.completions.get() == 0);
    }

    @Test
    void actorStateIsNotResolvedBeforeWorldExecutorRuns() {
        WorldFixture fixture = new WorldFixture();
        fixture.current.set(fixture.scheduled);
        fixture.runScheduledTask = false;
        HytaleUuidCompletionDispatcher dispatcher =
                new HytaleUuidCompletionDispatcher(fixture);

        assertTrue(dispatcher.dispatch(
                "world",
                ACTOR,
                (world, store, actorRef, actor) ->
                        fixture.completions.incrementAndGet()
        ));

        assertTrue(fixture.resolutions.get() == 0);
        assertTrue(fixture.completions.get() == 0);
        assertTrue(fixture.scheduledTask.get() != null);
    }

    private static final class WorldFixture
            implements HytaleUuidCompletionDispatcher.WorldAccess {
        private final World scheduled = world();
        private final World replacement = world();
        private final AtomicReference<World> current =
                new AtomicReference<>();
        private final AtomicInteger resolutions = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicReference<Runnable> scheduledTask =
                new AtomicReference<>();
        private Runnable afterSchedule = () -> { };
        private boolean runScheduledTask = true;

        @Override
        public World findWorld(String worldKey) {
            return current.get();
        }

        @Override
        public void execute(
                World world,
                Runnable task
        ) {
            scheduledTask.set(task);
            afterSchedule.run();
            if (runScheduledTask) {
                task.run();
            }
        }

        @Override
        public HytaleUuidCompletionDispatcher.ActorState resolveActor(
                World world,
                UUID actorUuid
        ) {
            resolutions.incrementAndGet();
            return null;
        }
    }

    private static World world() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (World) unsafe.allocateInstance(World.class);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
