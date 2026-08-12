package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.compat.HytaleParticleAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Runs delayed pairing visuals using stable UUIDs and world-thread entity resolution. */
final class BreedingPairingEffectsService {
    private static final String HEARTS_PARTICLE = "Hearts";
    private static final long PROXIMITY_CHECK_INTERVAL_MS = 100L;
    private static final long PROXIMITY_TIMEOUT_MS = 5000L;
    private static final long SPAWN_DELAY_AFTER_HEARTS_MS = 2200L;
    private static final double PAIRING_READY_DISTANCE = 2.20;

    private final BreedingParticleOffsetResolver particleOffsetResolver;

    BreedingPairingEffectsService(@Nonnull BreedingParticleOffsetResolver particleOffsetResolver) {
        this.particleOffsetResolver = particleOffsetResolver;
    }

    void schedule(@Nonnull World world,
                  @Nonnull UUID parentAUuid,
                  @Nonnull UUID parentBUuid,
                  @Nonnull Runnable spawnAction,
                  @Nonnull Runnable canceledAction) {
        ScheduledPairing pairing = new ScheduledPairing(
                world,
                parentAUuid,
                parentBUuid,
                spawnAction,
                canceledAction,
                new AtomicReference<>(PairingState.OPEN)
        );
        scheduleDelayed(pairing, PROXIMITY_CHECK_INTERVAL_MS,
                () -> awaitProximity(pairing, PROXIMITY_CHECK_INTERVAL_MS));
    }

    void spawnHearts(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        Vector3d position = new Vector3d(transform.getPosition());
        position.add(particleOffsetResolver.resolveOffset(npc));
        HytaleParticleAccess.spawn(HEARTS_PARTICLE, position, store);
    }

    private void awaitProximity(@Nonnull ScheduledPairing pairing, long elapsedMs) {
        Store<EntityStore> store = resolveStore(pairing.world());
        if (store == null) {
            fail(pairing);
            return;
        }
        Ref<EntityStore> parentA = pairing.world().getEntityRef(pairing.parentAUuid());
        Ref<EntityStore> parentB = pairing.world().getEntityRef(pairing.parentBUuid());
        if (isReady(parentA, parentB, store) || elapsedMs >= PROXIMITY_TIMEOUT_MS) {
            spawnHearts(parentA, store);
            spawnHearts(parentB, store);
            scheduleDelayed(pairing, SPAWN_DELAY_AFTER_HEARTS_MS, () -> complete(pairing));
            return;
        }
        scheduleDelayed(pairing, PROXIMITY_CHECK_INTERVAL_MS,
                () -> awaitProximity(pairing, elapsedMs + PROXIMITY_CHECK_INTERVAL_MS));
    }

    private static boolean isReady(@Nullable Ref<EntityStore> parentA,
                                   @Nullable Ref<EntityStore> parentB,
                                   @Nonnull Store<EntityStore> store) {
        if (parentA == null || !parentA.isValid() || parentB == null || !parentB.isValid()) {
            return false;
        }
        TransformComponent transformA = store.getComponent(parentA, TransformComponent.getComponentType());
        TransformComponent transformB = store.getComponent(parentB, TransformComponent.getComponentType());
        if (transformA == null || transformB == null) {
            return false;
        }
        double distance = transformA.getPosition().distance(transformB.getPosition());
        return Double.isFinite(distance) && distance <= PAIRING_READY_DISTANCE;
    }

    private void scheduleDelayed(@Nonnull ScheduledPairing pairing,
                                 long delayMs,
                                 @Nonnull Runnable action) {
        CompletableFuture.runAsync(
                () -> executeOnWorld(pairing, action),
                CompletableFuture.delayedExecutor(Math.max(0L, delayMs), TimeUnit.MILLISECONDS)
        ).exceptionally(failure -> {
            fail(pairing);
            return null;
        });
    }

    private void executeOnWorld(@Nonnull ScheduledPairing pairing, @Nonnull Runnable action) {
        if (pairing.state().get() != PairingState.OPEN || !pairing.world().isAlive()) {
            fail(pairing);
            return;
        }
        try {
            pairing.world().execute(() -> runWorldAction(pairing, action));
        } catch (RuntimeException | LinkageError failure) {
            fail(pairing);
        }
    }

    private static void runWorldAction(
            @Nonnull ScheduledPairing pairing,
            @Nonnull Runnable action
    ) {
        if (pairing.state().get() != PairingState.OPEN) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            fail(pairing);
        }
    }

    private static void complete(@Nonnull ScheduledPairing pairing) {
        if (!pairing.state().compareAndSet(PairingState.OPEN, PairingState.SPAWNING)) {
            return;
        }
        try {
            pairing.spawnAction().run();
            pairing.state().compareAndSet(PairingState.SPAWNING, PairingState.COMPLETED);
        } catch (RuntimeException | LinkageError failure) {
            if (pairing.state().compareAndSet(PairingState.SPAWNING, PairingState.CANCELED)) {
                pairing.canceledAction().run();
            }
        }
    }

    private static void fail(@Nonnull ScheduledPairing pairing) {
        if (pairing.state().compareAndSet(PairingState.OPEN, PairingState.CANCELED)) {
            pairing.canceledAction().run();
        }
    }

    @Nullable
    private static Store<EntityStore> resolveStore(@Nonnull World world) {
        return world.getEntityStore() == null ? null : world.getEntityStore().getStore();
    }

    private record ScheduledPairing(World world,
                                    UUID parentAUuid,
                                    UUID parentBUuid,
                                    Runnable spawnAction,
                                    Runnable canceledAction,
                                    AtomicReference<PairingState> state) {
    }

    private enum PairingState {
        OPEN,
        SPAWNING,
        COMPLETED,
        CANCELED
    }
}
