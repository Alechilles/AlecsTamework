package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Joins in-flight breeding preparation and every prepared capability into one durable cancel gate.
 *
 * <p>A capture cancellation remains pending until preparation has either produced and registered
 * its capability or proven that no capability was created. Capabilities are cancelled exactly once
 * per gate, and any ambiguous result permanently fails that gate closed.</p>
 */
final class BreedingPreparedCancellationCoordinator {
    private final Map<UUID, JobGate> gates = new ConcurrentHashMap<>();
    private final BreedingParentCaptureFenceIndex captureFences =
            new BreedingParentCaptureFenceIndex();

    boolean beginPreparation(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        return beginPreparation(storeScope, jobId, null, null);
    }

    boolean beginPreparation(@Nonnull Object storeScope,
                             @Nonnull UUID jobId,
                             @Nullable BreedingParentIdentity firstParent,
                             @Nullable BreedingParentIdentity secondParent) {
        return captureFences.beginIfAllowed(
                storeScope,
                firstParent,
                secondParent,
                () -> beginAfterPriorGates(
                        storeScope, jobId, firstParent, secondParent
                )
        );
    }

    boolean finishPreparation(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        JobGate gate = gates.get(Objects.requireNonNull(jobId, "jobId"));
        return gate != null && gate.finishPreparation(storeScope);
    }

    void failPreparation(@Nonnull Object storeScope, @Nonnull UUID jobId) {
        JobGate gate = gates.get(Objects.requireNonNull(jobId, "jobId"));
        if (gate != null) {
            gate.failPreparation(storeScope);
        }
    }

    void registerCapability(@Nonnull Object storeScope,
                            @Nonnull UUID jobId,
                            @Nonnull CancellationCapability capability) {
        gate(storeScope, jobId).register(storeScope, capability);
    }

    @Nonnull
    CompletableFuture<Boolean> cancelDurably(@Nonnull Object storeScope,
                                              @Nonnull UUID jobId,
                                              @Nonnull String reason) {
        return gate(storeScope, jobId).cancel(storeScope, normalizeReason(reason));
    }

    @Nullable
    CompletableFuture<Boolean> cancelDurablyByParent(
            @Nonnull Object storeScope,
            @Nonnull UUID parentUuid,
            @Nullable String stableProfileId,
            @Nonnull String reason) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(parentUuid, "parentUuid");
        String normalizedProfileId = normalizeProfileId(stableProfileId);
        BreedingParentCaptureFenceIndex.Acquisition<List<JobGate>> acquisition =
                captureFences.acquire(storeScope, parentUuid, normalizedProfileId, () -> {
                    List<JobGate> matching = new ArrayList<>();
                    for (JobGate candidate : List.copyOf(gates.values())) {
                        if (candidate.matchesParent(
                                storeScope, parentUuid, normalizedProfileId
                        )) {
                            matching.add(candidate);
                        }
                    }
                    return List.copyOf(matching);
                }
        );
        List<JobGate> matchingGates = acquisition.snapshot();
        List<CompletableFuture<Boolean>> matches = new ArrayList<>(matchingGates.size());
        for (JobGate gate : matchingGates) {
            matches.add(gate.cancel(storeScope, normalizeReason(reason)));
        }
        CompletableFuture<Boolean> barrier = matches.isEmpty()
                ? CompletableFuture.completedFuture(true)
                : allTerminal(matches);
        captureFences.retain(acquisition, barrier);
        return matches.isEmpty() ? null : barrier;
    }

    void releaseParentFence(@Nonnull Object storeScope,
                            @Nonnull UUID parentUuid,
                            @Nullable String stableProfileId,
                            boolean captured) {
        captureFences.release(
                storeScope, parentUuid, stableProfileId, captured
        );
    }

    void clearScope(@Nonnull Object storeScope) {
        captureFences.clearScope(storeScope);
        for (Map.Entry<UUID, JobGate> entry : List.copyOf(gates.entrySet())) {
            JobGate gate = entry.getValue();
            if (gate.belongsTo(storeScope) && gates.remove(entry.getKey(), gate)) {
                gate.failClosed();
            }
        }
    }

    void clearAll() {
        captureFences.clearAll();
        for (JobGate gate : List.copyOf(gates.values())) {
            gate.failClosed();
        }
        gates.clear();
    }

    private JobGate gate(Object storeScope, UUID jobId) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(jobId, "jobId");
        JobGate gate = gates.computeIfAbsent(jobId, ignored -> new JobGate(storeScope));
        if (!gate.belongsTo(storeScope)) {
            throw new IllegalStateException("Breeding cancellation gate belongs to another scope");
        }
        return gate;
    }

    private boolean beginAfterPriorGates(
            Object storeScope,
            UUID jobId,
            @Nullable BreedingParentIdentity firstParent,
            @Nullable BreedingParentIdentity secondParent) {
        for (Map.Entry<UUID, JobGate> entry : List.copyOf(gates.entrySet())) {
            JobGate prior = entry.getValue();
            if (!prior.matchesEitherParent(storeScope, firstParent, secondParent)) {
                continue;
            }
            CompletableFuture<Boolean> terminal = prior.cancel(
                    storeScope, "breeding-parent-reuse-fence"
            );
            if (!terminal.isDone() || !Boolean.TRUE.equals(terminal.getNow(false))) {
                return false;
            }
            gates.remove(entry.getKey(), prior);
        }
        return gate(storeScope, jobId).beginPreparation(
                storeScope, firstParent, secondParent
        );
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank()
                ? "breeding-population-canceled"
                : reason.trim();
    }

    @FunctionalInterface
    interface CancellationCapability {
        @Nonnull
        CompletableFuture<Boolean> cancel(@Nonnull String reason);
    }

    private static final class JobGate {
        private final Object storeScope;
        private final Map<CancellationCapability, CapabilityState> capabilities =
                new IdentityHashMap<>();
        private int preparations;
        private Set<UUID> parentUuids = Set.of();
        private Set<String> parentProfileIds = Set.of();
        private boolean cancellationRequested;
        private boolean failed;
        private String cancellationReason = "breeding-population-canceled";
        private CompletableFuture<Boolean> cancellation;

        private JobGate(Object storeScope) {
            this.storeScope = Objects.requireNonNull(storeScope, "storeScope");
        }

        private synchronized boolean belongsTo(Object expectedScope) {
            return storeScope == expectedScope;
        }

        private synchronized boolean beginPreparation(
                Object expectedScope,
                @Nullable BreedingParentIdentity firstParent,
                @Nullable BreedingParentIdentity secondParent) {
            requireScope(expectedScope);
            if (cancellationRequested) {
                return false;
            }
            parentUuids = parentUuids(firstParent, secondParent);
            parentProfileIds = parentProfileIds(firstParent, secondParent);
            preparations++;
            return true;
        }

        private boolean finishPreparation(Object expectedScope) {
            synchronized (this) {
                requireScope(expectedScope);
                if (preparations <= 0 || failed) {
                    failGateLocked();
                    return false;
                }
                preparations--;
                completeIfSettledLocked();
                return true;
            }
        }

        private synchronized void failPreparation(Object expectedScope) {
            requireScope(expectedScope);
            if (preparations > 0) {
                preparations--;
            }
            failGateLocked();
        }

        private void register(Object expectedScope, CancellationCapability capability) {
            CapabilityState toStart = null;
            synchronized (this) {
                requireScope(expectedScope);
                CapabilityState state = capabilities.computeIfAbsent(
                        Objects.requireNonNull(capability, "capability"),
                        CapabilityState::new
                );
                if (cancellationRequested && !state.started) {
                    state.started = true;
                    toStart = state;
                }
            }
            start(toStart);
        }

        private CompletableFuture<Boolean> cancel(Object expectedScope, String reason) {
            List<CapabilityState> toStart = new ArrayList<>();
            CompletableFuture<Boolean> result;
            synchronized (this) {
                requireScope(expectedScope);
                if (!cancellationRequested) {
                    cancellationRequested = true;
                    cancellationReason = reason;
                    cancellation = new CompletableFuture<>();
                }
                result = cancellation;
                for (CapabilityState state : capabilities.values()) {
                    if (!state.started) {
                        state.started = true;
                        toStart.add(state);
                    }
                }
                completeIfSettledLocked();
            }
            for (CapabilityState state : toStart) {
                start(state);
            }
            return result;
        }

        private synchronized boolean matchesParent(Object expectedScope,
                                                   UUID parentUuid,
                                                   @Nullable String profileId) {
            if (storeScope != expectedScope) {
                return false;
            }
            return parentUuids.contains(parentUuid)
                    || profileId != null && parentProfileIds.contains(profileId);
        }

        private synchronized boolean matchesEitherParent(
                Object expectedScope,
                @Nullable BreedingParentIdentity first,
                @Nullable BreedingParentIdentity second) {
            return storeScope == expectedScope && (matchesIdentity(first) || matchesIdentity(second));
        }

        private boolean matchesIdentity(@Nullable BreedingParentIdentity parent) {
            return parent != null && (parentUuids.contains(parent.entityUuid())
                    || parentProfileIds.contains(parent.profileId()));
        }

        private void start(CapabilityState state) {
            if (state == null) {
                return;
            }
            CompletableFuture<Boolean> completion;
            try {
                completion = state.capability.cancel(cancellationReason);
            } catch (RuntimeException | LinkageError failure) {
                completion = null;
            }
            if (completion == null) {
                capabilityFinished(state, false);
                return;
            }
            completion.whenComplete((terminal, failure) -> capabilityFinished(
                    state,
                    failure == null && Boolean.TRUE.equals(terminal)
            ));
        }

        private synchronized void capabilityFinished(CapabilityState state, boolean terminal) {
            state.finished = true;
            state.terminal = terminal;
            completeIfSettledLocked();
        }

        private void completeIfSettledLocked() {
            if (!cancellationRequested || cancellation == null || cancellation.isDone()) {
                return;
            }
            for (CapabilityState state : capabilities.values()) {
                if (state.finished && !state.terminal) {
                    cancellation.complete(false);
                    return;
                }
            }
            if (preparations > 0) {
                return;
            }
            for (CapabilityState state : capabilities.values()) {
                if (!state.finished) {
                    return;
                }
            }
            cancellation.complete(true);
        }

        private synchronized void failClosed() {
            failGateLocked();
        }

        private void failGateLocked() {
            failed = true;
            cancellationRequested = true;
            if (cancellation == null) {
                cancellation = new CompletableFuture<>();
            }
            cancellation.complete(false);
        }

        private void requireScope(Object expectedScope) {
            if (storeScope != expectedScope) {
                throw new IllegalStateException("Breeding cancellation gate scope changed");
            }
        }
    }

    private static Set<UUID> parentUuids(@Nullable BreedingParentIdentity first,
                                         @Nullable BreedingParentIdentity second) {
        if (first == null || second == null) {
            return Set.of();
        }
        return Set.of(first.entityUuid(), second.entityUuid());
    }

    private static Set<String> parentProfileIds(@Nullable BreedingParentIdentity first,
                                                @Nullable BreedingParentIdentity second) {
        if (first == null || second == null) {
            return Set.of();
        }
        return Set.of(first.profileId(), second.profileId());
    }

    private static CompletableFuture<Boolean> allTerminal(
            List<CompletableFuture<Boolean>> completions) {
        CompletableFuture<?>[] waits = completions.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(waits).handle((ignored, failure) -> {
            if (failure != null) {
                return false;
            }
            for (CompletableFuture<Boolean> completion : completions) {
                if (!Boolean.TRUE.equals(completion.getNow(false))) {
                    return false;
                }
            }
            return true;
        });
    }

    @Nullable
    private static String normalizeProfileId(@Nullable String profileId) {
        if (profileId == null) {
            return null;
        }
        String normalized = profileId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static final class CapabilityState {
        private final CancellationCapability capability;
        private boolean started;
        private boolean finished;
        private boolean terminal;

        private CapabilityState(CancellationCapability capability) {
            this.capability = capability;
        }
    }

}
