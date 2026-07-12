package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Retries family-flock assignment without retaining entity references or stores across threads.
 */
final class BreedingFamilyFlockRetryService {
    private static final long RETRY_INTERVAL_MS = 100L;
    private static final int MAX_ATTEMPTS = 12;

    private final BreedingFamilyFlockService familyFlockService;

    BreedingFamilyFlockRetryService(@Nonnull BreedingFamilyFlockService familyFlockService) {
        this.familyFlockService = Objects.requireNonNull(
                familyFlockService, "familyFlockService"
        );
    }

    void schedule(@Nullable Ref<EntityStore> childRef,
                  @Nullable Ref<EntityStore> parentARef,
                  @Nullable Ref<EntityStore> parentBRef,
                  @Nullable Store<EntityStore> store) {
        if (store == null) {
            return;
        }
        World world = store.getExternalData() != null
                ? store.getExternalData().getWorld() : null;
        UUID childUuid = entityUuid(childRef, store);
        UUID parentAUuid = entityUuid(parentARef, store);
        UUID parentBUuid = entityUuid(parentBRef, store);
        if (world == null || childUuid == null || parentAUuid == null || parentBUuid == null) {
            return;
        }
        schedule(world, childUuid, parentAUuid, parentBUuid, MAX_ATTEMPTS);
    }

    private void schedule(World world,
                          UUID childUuid,
                          UUID parentAUuid,
                          UUID parentBUuid,
                          int attemptsRemaining) {
        if (attemptsRemaining <= 0) {
            return;
        }
        CompletableFuture.runAsync(
                () -> world.execute(() -> retry(
                        world, childUuid, parentAUuid, parentBUuid, attemptsRemaining
                )),
                CompletableFuture.delayedExecutor(RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private void retry(World world,
                       UUID childUuid,
                       UUID parentAUuid,
                       UUID parentBUuid,
                       int attemptsRemaining) {
        Store<EntityStore> store = world.getEntityStore() != null
                ? world.getEntityStore().getStore() : null;
        Ref<EntityStore> childRef = world.getEntityRef(childUuid);
        Ref<EntityStore> parentARef = world.getEntityRef(parentAUuid);
        Ref<EntityStore> parentBRef = world.getEntityRef(parentBUuid);
        if (store == null || childRef == null || !childRef.isValid()) {
            return;
        }
        boolean assigned = familyFlockService.assignFamilyFlock(
                childRef, parentARef, parentBRef, store
        );
        if (!assigned) {
            schedule(
                    world, childUuid, parentAUuid, parentBUuid, attemptsRemaining - 1
            );
        }
    }

    @Nullable
    private static UUID entityUuid(@Nullable Ref<EntityStore> ref,
                                   @Nonnull Store<EntityStore> store) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        if (UUIDComponent.getComponentType() == null) {
            return null;
        }
        UUIDComponent identity = store.getComponent(ref, UUIDComponent.getComponentType());
        return identity != null ? identity.getUuid() : null;
    }
}
