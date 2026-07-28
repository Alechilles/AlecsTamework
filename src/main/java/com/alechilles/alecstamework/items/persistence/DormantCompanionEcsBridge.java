package com.alechilles.alecstamework.items.persistence;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Submits immutable Hytale lifecycle evidence to the replacement dormant author.
 *
 * <p>Only stable observation keys survive the synchronous author call. ECS references and stores
 * are used while the author freezes its snapshot and are never retained by async continuations.</p>
 */
public final class DormantCompanionEcsBridge {
    private final ObservationFactory observations;
    private final DormantAuthor author;
    private final Consumer<Completion> completions;
    private final Executor completionExecutor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Creates the production bridge. */
    public DormantCompanionEcsBridge(
            @Nonnull ObservationFactory observations,
            @Nonnull PositiveEvidenceDormantAuthor author,
            @Nonnull Consumer<Completion> completions
    ) {
        this(
                observations,
                author::makeDormant,
                completions,
                ForkJoinPool.commonPool()
        );
    }

    DormantCompanionEcsBridge(
            ObservationFactory observations,
            DormantAuthor author,
            Consumer<Completion> completions
    ) {
        this(observations, author, completions, Runnable::run);
    }

    DormantCompanionEcsBridge(
            ObservationFactory observations,
            DormantAuthor author,
            Consumer<Completion> completions,
            Executor completionExecutor
    ) {
        this.observations = Objects.requireNonNull(
                observations, "observations"
        );
        this.author = Objects.requireNonNull(author, "author");
        this.completions = Objects.requireNonNull(
                completions, "completions"
        );
        this.completionExecutor = Objects.requireNonNull(
                completionExecutor, "completionExecutor"
        );
    }

    /** Handles one saved DeathComponent addition. */
    public boolean onDeath(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull DeathComponent death,
            @Nonnull Store<EntityStore> store
    ) {
        try {
            return submit(
                    observations.death(reference, death, store),
                    reference,
                    store
            );
        } catch (RuntimeException | LinkageError failure) {
            completeAsync(Completion.failed(
                    "death_evidence_freeze_failed", failure
            ));
            return false;
        }
    }

    /** Handles only explicit destructive entity removal. */
    public boolean onRemoval(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store
    ) {
        try {
            return submit(
                    observations.removal(reference, reason, store),
                    reference,
                    store
            );
        } catch (RuntimeException | LinkageError failure) {
            completeAsync(Completion.failed(
                    "removal_evidence_freeze_failed", failure
            ));
            return false;
        }
    }

    /** Handles one entity observed inside an authoritative world-deletion event. */
    public boolean onWorldDeletion(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store
    ) {
        try {
            return submit(
                    observations.worldDeletion(reference, store),
                    reference,
                    store
            );
        } catch (RuntimeException | LinkageError failure) {
            completeAsync(Completion.failed(
                    "world_deletion_evidence_freeze_failed", failure
            ));
            return false;
        }
    }

    private boolean submit(
            @Nullable FrozenObservation frozen,
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        if (frozen == null) {
            return false;
        }
        String key = frozen.observation().observationKey();
        if (!inFlight.add(key)) {
            return false;
        }
        final CompletionStage<CompanionLifecycleAuthorResult> stage;
        try {
            stage = author.makeDormant(
                    new PositiveEvidenceDormantAuthor.Intent(
                            frozen.observation(),
                            reference,
                            store,
                            frozen.roleId()
                    )
            );
        } catch (RuntimeException | LinkageError failure) {
            inFlight.remove(key);
            completeAsync(new Completion(key, null, failure));
            return false;
        }
        if (stage == null) {
            inFlight.remove(key);
            completeAsync(Completion.failed(
                    key, new IllegalStateException(
                            "Dormant author returned no completion stage"
                    )
            ));
            return false;
        }
        stage.whenCompleteAsync(
                (result, failure) -> complete(key, result, failure),
                completionExecutor
        );
        return true;
    }

    private void complete(
            String key,
            CompanionLifecycleAuthorResult result,
            Throwable failure
    ) {
        inFlight.remove(key);
        completions.accept(new Completion(key, result, failure));
    }

    private void completeAsync(Completion completion) {
        completionExecutor.execute(() -> completions.accept(completion));
    }

    /** Immutable observation whose ECS evidence is supplied only to the synchronous author call. */
    public record FrozenObservation(
            @Nonnull DormantCompanionObservation observation,
            @Nonnull String roleId
    ) {
        public FrozenObservation {
            Objects.requireNonNull(observation, "observation");
            if (roleId == null || roleId.isBlank()) {
                throw new IllegalArgumentException("Dormant role is required");
            }
            roleId = roleId.trim();
        }
    }

    /** Immutable terminal bridge report suitable for bounded logging. */
    public record Completion(
            @Nonnull String observationKey,
            @Nullable CompanionLifecycleAuthorResult result,
            @Nullable Throwable failure
    ) {
        public Completion {
            if (observationKey == null || observationKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Dormant completion key is required"
                );
            }
            observationKey = observationKey.trim();
        }

        static Completion failed(String key, Throwable failure) {
            return new Completion(key, null, failure);
        }
    }

    /** Produces immutable evidence while the Hytale callback is still active. */
    public interface ObservationFactory {
        @Nullable
        FrozenObservation death(
                Ref<EntityStore> reference,
                DeathComponent death,
                Store<EntityStore> store
        );

        @Nullable
        FrozenObservation removal(
                Ref<EntityStore> reference,
                RemoveReason reason,
                Store<EntityStore> store
        );

        @Nullable
        FrozenObservation worldDeletion(
                Ref<EntityStore> reference,
                Store<EntityStore> store
        );
    }

    @FunctionalInterface
    interface DormantAuthor {
        CompletionStage<CompanionLifecycleAuthorResult> makeDormant(
                PositiveEvidenceDormantAuthor.Intent intent
        );
    }
}
