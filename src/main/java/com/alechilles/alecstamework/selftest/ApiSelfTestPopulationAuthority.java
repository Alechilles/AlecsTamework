package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Routes shipped API self-test ownership through the canonical journaled population authority.
 */
final class ApiSelfTestPopulationAuthority {
    private static final long WRITE_QUEUE_WAIT_TIMEOUT_MS = 5000L;

    private final TameworkPersistenceRuntime persistence;
    private final OwnerMutationScheduler scheduler;

    ApiSelfTestPopulationAuthority(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull OwnerPopulationRuntime populationRuntime
    ) {
        this(
                persistence,
                Objects.requireNonNull(populationRuntime, "populationRuntime").mutationScheduler()
        );
    }

    ApiSelfTestPopulationAuthority(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull OwnerMutationScheduler scheduler
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Nonnull
    CompletableFuture<Void> assignOwnerAsync(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> reference,
            @Nonnull ApiSelfTestFixtureRecord fixture,
            @Nonnull String fixtureSetId
    ) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        boolean scheduled = scheduler.schedule(
                reference,
                store,
                fixture.ownerUuid(),
                fixture.ownerName(),
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.ADMIN_FORCE,
                true,
                "api-self-test-owner:" + fixtureSetId + ":" + fixture.fixtureKey(),
                terminalCallbacks(completion)
        );
        failIfNotScheduled(
                scheduled, completion, "fixture-owner-admission-not-scheduled:" + fixture.fixtureKey()
        );
        return completion;
    }

    @Nonnull
    CompletableFuture<Void> persistMetadataAsync(
            @Nonnull ApiSelfTestFixtureRecord owned,
            @Nonnull ApiSelfTestFixtureRecord stranger,
            @Nonnull String fixtureSetId,
            @Nonnull UUID ownerPlayerUuid,
            @Nonnull String toolId
    ) {
        if (!upsertMetadata(owned, fixtureSetId, ownerPlayerUuid, toolId)
                || !upsertMetadata(stranger, fixtureSetId, ownerPlayerUuid, toolId)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("fixture-profile-write-not-accepted")
            );
        }
        return CompletableFuture.supplyAsync(() ->
                persistence.awaitWriteQueueIdle(WRITE_QUEUE_WAIT_TIMEOUT_MS)
        ).thenCompose(idle -> idle
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(
                        new IllegalStateException("fixture-profile-write-timeout")
                ));
    }

    @Nonnull
    CompletableFuture<Void> releaseLoadedAsync(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> reference,
            @Nonnull UUID npcUuid
    ) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null
                ? null
                : store.getComponent(reference, ownerType);
        if (owner == null || owner.getOwnerId() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "fixture-owner-component-unavailable:" + npcUuid
            ));
        }
        return scheduleRelease(store, reference, npcUuid);
    }

    @Nonnull
    CompletableFuture<Void> releaseOrDespawnUnownedAsync(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> reference,
            @Nonnull UUID npcUuid
    ) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null
                ? null
                : store.getComponent(reference, ownerType);
        if (owner != null && owner.getOwnerId() != null) {
            return scheduleRelease(store, reference, npcUuid);
        }
        despawn(store, reference);
        return CompletableFuture.completedFuture(null);
    }

    @Nonnull
    private CompletableFuture<Void> scheduleRelease(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> reference,
            @Nonnull UUID npcUuid
    ) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        OwnerMutationScheduler.MutationCallbacks callbacks = terminalCallbacks(completion, () ->
                despawn(store, reference)
        );
        boolean scheduled = scheduler.schedulePermanentRelease(
                reference,
                store,
                true,
                "api-self-test-release:" + npcUuid,
                callbacks
        );
        failIfNotScheduled(scheduled, completion, "fixture-release-not-scheduled:" + npcUuid);
        return completion;
    }

    @Nonnull
    private OwnerMutationScheduler.MutationCallbacks terminalCallbacks(
            @Nonnull CompletableFuture<Void> completion
    ) {
        return terminalCallbacks(completion, () -> {
        });
    }

    @Nonnull
    private OwnerMutationScheduler.MutationCallbacks terminalCallbacks(
            @Nonnull CompletableFuture<Void> completion,
            @Nonnull Runnable committedAction
    ) {
        return new OwnerMutationScheduler.MutationCallbacks() {
            @Override
            public void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
                completion.completeExceptionally(new IllegalStateException(reason));
            }

            @Override
            public void onPopulationCommitted(@Nonnull CompanionPopulationCommitResult result) {
                try {
                    committedAction.run();
                    completion.complete(null);
                } catch (Throwable throwable) {
                    completion.completeExceptionally(throwable);
                }
            }

            @Override
            public void onWorldDispatchRejected(
                    @Nonnull String reason,
                    boolean mutationApplied,
                    @Nullable CompanionPopulationCommitResult commit
            ) {
                completion.completeExceptionally(new IllegalStateException(reason));
            }

            @Override
            public void onDurabilityDegraded(@Nonnull String reason) {
                completion.completeExceptionally(new IllegalStateException(reason));
            }
        };
    }

    private boolean upsertMetadata(
            @Nonnull ApiSelfTestFixtureRecord fixture,
            @Nonnull String fixtureSetId,
            @Nonnull UUID ownerPlayerUuid,
            @Nonnull String toolId
    ) {
        String profileJson = "{\"apiSelfTest\":true,\"fixtureSetId\":\""
                + fixtureSetId
                + "\",\"fixtureKey\":\""
                + fixture.fixtureKey()
                + "\",\"ownerPlayerUuid\":\""
                + ownerPlayerUuid
                + "\"}";
        return persistence.getNpcProfileRepository().upsertSnapshotAsync(
                new NpcProfileRepository.ProfileUpdate(
                        fixture.npcUuid(),
                        null,
                        null,
                        fixture.roleId(),
                        fixture.displayName(),
                        fixture.displayName(),
                        true,
                        null,
                        null,
                        profileJson,
                        new String[] { toolId }
                )
        );
    }

    private static void failIfNotScheduled(
            boolean scheduled,
            @Nonnull CompletableFuture<Void> completion,
            @Nonnull String reason
    ) {
        if (!scheduled && !completion.isDone()) {
            completion.completeExceptionally(new IllegalStateException(reason));
        }
    }

    private static void despawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> reference
    ) {
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc != null) {
            npc.setToDespawn();
        }
    }
}
