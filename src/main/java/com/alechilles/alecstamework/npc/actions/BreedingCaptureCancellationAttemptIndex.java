package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.util.StoreScopedState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Retains one capture-cancellation barrier across synchronous retries for either parent. */
final class BreedingCaptureCancellationAttemptIndex {
    private final StoreScopedState<ScopeIndex> scopes = new StoreScopedState<>(ScopeIndex::new);

    void remember(@Nonnull Object storeScope,
                  @Nonnull BreedingBirthJob job,
                  @Nonnull UUID capturedLiveUuid,
                  @Nonnull CompletableFuture<BreedingCaptureCancellationService.CancellationResult>
                          completion) {
        ScopeIndex index = scopes.get(storeScope);
        synchronized (index) {
            index(completion, index, job.firstParent());
            index(completion, index, job.secondParent());
            add(index.byUuid, capturedLiveUuid, completion);
        }
    }

    void remember(@Nonnull Object storeScope,
                  @Nonnull UUID capturedLiveUuid,
                  @Nullable String stableProfileId,
                  @Nonnull CompletableFuture<
                          BreedingCaptureCancellationService.CancellationResult> completion) {
        ScopeIndex index = scopes.get(storeScope);
        synchronized (index) {
            add(index.byUuid, capturedLiveUuid, completion);
            if (stableProfileId != null) {
                add(index.byProfile, stableProfileId, completion);
            }
        }
    }

    @Nonnull
    List<CompletableFuture<BreedingCaptureCancellationService.CancellationResult>> findAll(
            @Nonnull Object storeScope,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId) {
        ScopeIndex index = scopes.get(storeScope);
        synchronized (index) {
            List<CompletableFuture<BreedingCaptureCancellationService.CancellationResult>> found =
                    new ArrayList<>();
            addAllDistinct(found, index.byUuid.get(capturedParentUuid));
            if (stableProfileId != null) {
                addAllDistinct(found, index.byProfile.get(stableProfileId));
            }
            return List.copyOf(found);
        }
    }

    private static void index(
            CompletableFuture<BreedingCaptureCancellationService.CancellationResult> completion,
            ScopeIndex index,
            BreedingParentIdentity identity) {
        add(index.byUuid, identity.entityUuid(), completion);
        add(index.byProfile, identity.profileId(), completion);
    }

    private static <K> void add(
            Map<K, List<CompletableFuture<
                    BreedingCaptureCancellationService.CancellationResult>>> target,
            K key,
            CompletableFuture<BreedingCaptureCancellationService.CancellationResult> completion) {
        List<CompletableFuture<BreedingCaptureCancellationService.CancellationResult>> values =
                target.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!values.contains(completion)) {
            values.add(completion);
        }
    }

    private static void addAllDistinct(
            List<CompletableFuture<BreedingCaptureCancellationService.CancellationResult>> target,
            @Nullable List<CompletableFuture<
                    BreedingCaptureCancellationService.CancellationResult>> candidates) {
        if (candidates == null) {
            return;
        }
        for (CompletableFuture<BreedingCaptureCancellationService.CancellationResult> candidate
                : candidates) {
            if (!target.contains(candidate)) {
                target.add(candidate);
            }
        }
    }

    private static final class ScopeIndex {
        private final Map<UUID, List<CompletableFuture<
                BreedingCaptureCancellationService.CancellationResult>>> byUuid = new HashMap<>();
        private final Map<String, List<CompletableFuture<
                BreedingCaptureCancellationService.CancellationResult>>> byProfile = new HashMap<>();
    }
}
