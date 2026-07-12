package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns scoped parent-identity fences across cancellation and managed-capture completion. */
final class BreedingParentCaptureFenceIndex {
    private final Object lock = new Object();
    private final Map<Object, List<ParentCaptureFence>> fencesByScope = new IdentityHashMap<>();

    boolean beginIfAllowed(@Nonnull Object storeScope,
                           @Nullable BreedingParentIdentity firstParent,
                           @Nullable BreedingParentIdentity secondParent,
                           @Nonnull BooleanSupplier begin) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(begin, "begin");
        synchronized (lock) {
            List<ParentCaptureFence> fences = fencesByScope.get(storeScope);
            if (fences != null) {
                for (ParentCaptureFence fence : fences) {
                    if (fence.blocks(firstParent, secondParent)) {
                        return false;
                    }
                }
            }
            return begin.getAsBoolean();
        }
    }

    @Nonnull
    <T> Acquisition<T> acquire(@Nonnull Object storeScope,
                               @Nonnull UUID parentUuid,
                               @Nullable String stableProfileId,
                               @Nonnull Supplier<T> snapshot) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(parentUuid, "parentUuid");
        Objects.requireNonNull(snapshot, "snapshot");
        String profileId = normalizeProfileId(stableProfileId);
        synchronized (lock) {
            List<ParentCaptureFence> fences = fencesByScope.computeIfAbsent(
                    storeScope, ignored -> new ArrayList<>()
            );
            ParentCaptureFence fence = matchingFence(fences, parentUuid, profileId);
            if (fence == null) {
                fence = new ParentCaptureFence();
                fences.add(fence);
            }
            fence.acquire(parentUuid, profileId);
            return new Acquisition<>(fence, snapshot.get());
        }
    }

    void retain(@Nonnull Acquisition<?> acquisition,
                @Nonnull CompletableFuture<Boolean> barrier) {
        Objects.requireNonNull(acquisition, "acquisition");
        Objects.requireNonNull(barrier, "barrier");
        synchronized (lock) {
            acquisition.fence.retain(barrier);
        }
    }

    void release(@Nonnull Object storeScope,
                 @Nonnull UUID parentUuid,
                 @Nullable String stableProfileId,
                 boolean captured) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(parentUuid, "parentUuid");
        String profileId = normalizeProfileId(stableProfileId);
        Map<ParentCaptureFence, List<CompletableFuture<Boolean>>> watches =
                new IdentityHashMap<>();
        synchronized (lock) {
            List<ParentCaptureFence> fences = fencesByScope.get(storeScope);
            if (fences == null) {
                return;
            }
            for (ParentCaptureFence fence : List.copyOf(fences)) {
                if (!fence.matches(parentUuid, profileId)) {
                    continue;
                }
                fence.release(captured);
                if (fence.releasable()) {
                    fences.remove(fence);
                } else {
                    watches.put(fence, fence.pendingBarriers());
                }
            }
            removeEmptyScope(storeScope, fences);
        }
        watch(storeScope, watches);
    }

    void clearScope(@Nonnull Object storeScope) {
        synchronized (lock) {
            fencesByScope.remove(Objects.requireNonNull(storeScope, "storeScope"));
        }
    }

    void clearAll() {
        synchronized (lock) {
            fencesByScope.clear();
        }
    }

    private void watch(
            Object storeScope,
            Map<ParentCaptureFence, List<CompletableFuture<Boolean>>> watches) {
        for (Map.Entry<ParentCaptureFence, List<CompletableFuture<Boolean>>> watch
                : watches.entrySet()) {
            for (CompletableFuture<Boolean> barrier : watch.getValue()) {
                barrier.whenComplete(
                        (ignored, failure) -> recheck(storeScope, watch.getKey())
                );
            }
        }
    }

    private void recheck(Object storeScope, ParentCaptureFence fence) {
        synchronized (lock) {
            List<ParentCaptureFence> fences = fencesByScope.get(storeScope);
            if (fences == null || !fences.contains(fence) || !fence.releasable()) {
                return;
            }
            fences.remove(fence);
            removeEmptyScope(storeScope, fences);
        }
    }

    private void removeEmptyScope(Object storeScope, List<ParentCaptureFence> fences) {
        if (fences.isEmpty()) {
            fencesByScope.remove(storeScope);
        }
    }

    @Nullable
    private static ParentCaptureFence matchingFence(
            List<ParentCaptureFence> fences,
            UUID parentUuid,
            @Nullable String profileId) {
        for (ParentCaptureFence fence : fences) {
            if (fence.matches(parentUuid, profileId)) {
                return fence;
            }
        }
        return null;
    }

    @Nullable
    private static String normalizeProfileId(@Nullable String profileId) {
        if (profileId == null) {
            return null;
        }
        String normalized = profileId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static final class Acquisition<T> {
        private final ParentCaptureFence fence;
        private final T snapshot;

        private Acquisition(ParentCaptureFence fence, T snapshot) {
            this.fence = fence;
            this.snapshot = snapshot;
        }

        T snapshot() {
            return snapshot;
        }
    }

    private static final class ParentCaptureFence {
        private final Set<UUID> parentUuids = new java.util.HashSet<>();
        private final Set<String> parentProfileIds = new java.util.HashSet<>();
        private final List<CompletableFuture<Boolean>> barriers = new ArrayList<>();
        private int holds;
        private boolean releaseRequested;
        private boolean captured;

        private void acquire(UUID parentUuid, @Nullable String profileId) {
            parentUuids.add(parentUuid);
            if (profileId != null) {
                parentProfileIds.add(profileId);
            }
            holds++;
        }

        private void retain(CompletableFuture<Boolean> barrier) {
            if (!barriers.contains(barrier)) {
                barriers.add(barrier);
            }
        }

        private void release(boolean capturedOutcome) {
            if (holds > 0) {
                holds--;
            }
            releaseRequested = true;
            captured |= capturedOutcome;
        }

        private boolean matches(UUID parentUuid, @Nullable String profileId) {
            return parentUuids.contains(parentUuid)
                    || profileId != null && parentProfileIds.contains(profileId);
        }

        private boolean blocks(@Nullable BreedingParentIdentity first,
                               @Nullable BreedingParentIdentity second) {
            return blocked(first) || blocked(second);
        }

        private boolean blocked(@Nullable BreedingParentIdentity parent) {
            return parent != null && (parentUuids.contains(parent.entityUuid())
                    || parentProfileIds.contains(parent.profileId()));
        }

        private boolean releasable() {
            if (captured) {
                return true;
            }
            if (!releaseRequested || holds > 0) {
                return false;
            }
            for (CompletableFuture<Boolean> barrier : barriers) {
                if (!barrier.isDone() || !Boolean.TRUE.equals(barrier.getNow(false))) {
                    return false;
                }
            }
            return true;
        }

        private List<CompletableFuture<Boolean>> pendingBarriers() {
            List<CompletableFuture<Boolean>> pending = new ArrayList<>();
            for (CompletableFuture<Boolean> barrier : barriers) {
                if (!barrier.isDone()) {
                    pending.add(barrier);
                }
            }
            return List.copyOf(pending);
        }
    }
}
