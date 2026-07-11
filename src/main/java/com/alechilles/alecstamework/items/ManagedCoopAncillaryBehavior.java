package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.DispatchOutcome;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Schema-v5 ancillary behavior for managed-coop produce and block interaction presentation.
 *
 * <p>Production is queued only after the planned release attempt settles. The queued callback
 * resolves the world/block again and reads the then-current coherent resident index, so a
 * finalized release is excluded while a release that left the resident HOUSED remains eligible.
 * No live game object is retained by this service.</p>
 */
public final class ManagedCoopAncillaryBehavior
        implements ManagedCoopRuntimeSweepOrchestrator.AncillaryBehavior {
    static final long GAME_MILLIS_PER_HOUR = 3_600_000L;
    static final int MINIMUM_PRODUCE_INTERVAL_HOURS = 24;
    static final String INTERACTION_STATE_EMPTY = "default";
    static final String INTERACTION_STATE_PRESENT = "Produce_Ready";

    public enum OutcomeStatus {
        PRODUCED,
        NOT_DUE,
        NO_ELIGIBLE_RESIDENTS,
        INTERACTION_STATE_SYNCED,
        INDEX_UNTRUSTED,
        BLOCK_UNAVAILABLE,
        QUEUE_UNAVAILABLE,
        FAILED
    }

    /** Compact diagnostics emitted once per queued request, never once per produced item. */
    public record Outcome(@Nonnull OutcomeStatus status,
                          @Nonnull ManagedCoopAuthorityKey authorityKey,
                          int eligibleResidents,
                          int productionInvocations,
                          @Nullable String detail) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(authorityKey, "authorityKey");
        }
    }

    /** Exact paired index publication observed while ancillary work holds the refresh lock. */
    public record CompositeEpoch(long residentRevision, long operationRevision) {
        public CompositeEpoch {
            if (residentRevision < 1L || operationRevision < 1L) {
                throw new IllegalArgumentException("composite revisions must be positive");
            }
        }
    }

    enum InventoryApplyStatus {
        APPLIED,
        SATURATED_UNCHANGED,
        POSSIBLY_PARTIAL
    }

    /** Mutation evidence from one generated drop attempt. */
    record InventoryApply(@Nonnull InventoryApplyStatus status, @Nullable String detail) {
        InventoryApply {
            Objects.requireNonNull(status, "status");
        }

        static InventoryApply applied() {
            return new InventoryApply(InventoryApplyStatus.APPLIED, null);
        }

        static InventoryApply saturated() {
            return new InventoryApply(
                    InventoryApplyStatus.SATURATED_UNCHANGED,
                    "managed_coop_produce_container_saturated");
        }

        static InventoryApply possiblyPartial(@Nullable String detail) {
            return new InventoryApply(
                    InventoryApplyStatus.POSSIBLY_PARTIAL,
                    detail != null ? detail : "managed_coop_produce_partial_or_unknown");
        }
    }

    private final ManagedCoopResidentIndex residentIndex;
    private final EpochGateway epochs;
    private final RuntimeGateway runtime;
    private final OutcomeSink outcomes;
    private final ConcurrentHashMap<SlotCadenceKey, Long> lastProducedAtBySlot =
            new ConcurrentHashMap<>();

    public ManagedCoopAncillaryBehavior(
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this(residentIndex, new CompositeEpochGateway(
                        residentIndex, operationIndex, compositeIndexes),
                new HytaleManagedCoopAncillaryGateway(), OutcomeSink.noop());
    }

    public ManagedCoopAncillaryBehavior(
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull OutcomeSink outcomes) {
        this(residentIndex, new CompositeEpochGateway(
                        residentIndex, operationIndex, compositeIndexes),
                new HytaleManagedCoopAncillaryGateway(), outcomes);
    }

    ManagedCoopAncillaryBehavior(
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull EpochGateway epochs,
            @Nonnull RuntimeGateway runtime,
            @Nonnull OutcomeSink outcomes) {
        this.residentIndex = Objects.requireNonNull(residentIndex, "residentIndex");
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    /**
     * Waits for the single release attempt, if any, then queues production by immutable location.
     * Failure and success both settle the gate; current HOUSED evidence decides eligibility.
     */
    @Override
    public void produceAfter(
            @Nonnull ManagedCoopAncillaryRequest request,
            @Nullable CompletionStage<DispatchOutcome> precedingRelease) {
        Objects.requireNonNull(request, "request");
        CompletionStage<?> settled = precedingRelease != null
                ? precedingRelease : CompletableFuture.completedFuture(null);
        settled.handle((ignored, failure) -> request).thenAccept(this::enqueueProduction);
    }

    /** Queues present/empty block presentation without retaining the discovery-time container. */
    @Override
    public void syncInteractionState(@Nonnull ManagedCoopAncillaryRequest request) {
        Objects.requireNonNull(request, "request");
        enqueue(request, false);
    }

    /** Removes cadence state for coops no longer present in a reliable managed-context scan. */
    @Override
    public void retainActiveCoops(@Nonnull Set<String> activeCoopKeys) {
        Objects.requireNonNull(activeCoopKeys, "activeCoopKeys");
        Set<String> stableKeys = Set.copyOf(activeCoopKeys);
        lastProducedAtBySlot.keySet().removeIf(key -> !stableKeys.contains(key.coopKey()));
    }

    private void enqueueProduction(ManagedCoopAncillaryRequest request) {
        enqueue(request, true);
    }

    private void enqueue(ManagedCoopAncillaryRequest request, boolean produce) {
        final boolean accepted;
        try {
            accepted = runtime.enqueue(
                    request.authorityKey().worldName(),
                    () -> runOnOwningThread(request, produce));
        } catch (RuntimeException exception) {
            emit(outcome(OutcomeStatus.FAILED, request, 0, 0,
                    failureDetail("managed_coop_ancillary_enqueue", exception)));
            return;
        }
        if (!accepted) {
            emit(outcome(OutcomeStatus.QUEUE_UNAVAILABLE, request, 0, 0,
                    "managed_coop_ancillary_world_unavailable"));
        }
    }

    private void runOnOwningThread(ManagedCoopAncillaryRequest request, boolean produce) {
        try {
            synchronized (epochs.lock()) {
                CompositeEpoch epoch = epochs.capture();
                if (epoch == null) {
                    emit(outcome(OutcomeStatus.INDEX_UNTRUSTED, request, 0, 0,
                            "managed_coop_composite_index_untrusted"));
                    return;
                }
                ManagedCoopResidentIndex.Snapshot snapshot = residentIndex.snapshot();
                if (snapshot.revision() != epoch.residentRevision()) {
                    emit(outcome(OutcomeStatus.INDEX_UNTRUSTED, request, 0, 0,
                            "managed_coop_composite_index_epoch_changed"));
                    return;
                }
                BlockAccess block = runtime.resolve(request);
                if (block == null) {
                    emit(outcome(OutcomeStatus.BLOCK_UNAVAILABLE, request, 0, 0,
                            "managed_coop_typed_block_or_container_unavailable"));
                    return;
                }
                if (!epochs.isCurrent(epoch)) {
                    emit(outcome(OutcomeStatus.INDEX_UNTRUSTED, request, 0, 0,
                            "managed_coop_composite_index_epoch_changed_before_apply"));
                    return;
                }
                if (!produce) {
                    block.setInteractionState(interactionState(block));
                    emit(outcome(OutcomeStatus.INTERACTION_STATE_SYNCED, request, 0, 0, null));
                    return;
                }
                produce(request, snapshot, block);
            }
        } catch (RuntimeException exception) {
            emit(outcome(OutcomeStatus.FAILED, request, 0, 0,
                    failureDetail("managed_coop_ancillary_apply", exception)));
        }
    }

    private void produce(ManagedCoopAncillaryRequest request,
                         ManagedCoopResidentIndex.Snapshot snapshot,
                         BlockAccess block) {
        List<ResidentRecord> eligible = eligibleResidents(request, snapshot);
        if (eligible.isEmpty()) {
            emit(outcome(OutcomeStatus.NO_ELIGIBLE_RESIDENTS, request, 0, 0, null));
            return;
        }
        int invocations = 0;
        boolean anyDue = false;
        boolean saturated = false;
        String saturationDetail = null;
        for (ResidentRecord resident : eligible) {
            ResidentProduction result = produceResident(request, resident, block);
            anyDue |= result.due();
            invocations += result.invocations();
            saturated = result.saturated();
            if (result.detail() != null) {
                saturationDetail = result.detail();
            }
            if (saturated) {
                break;
            }
        }
        if (anyDue) {
            block.setInteractionState(interactionState(block));
        }
        emit(outcome(
                anyDue ? OutcomeStatus.PRODUCED : OutcomeStatus.NOT_DUE,
                request, eligible.size(), invocations,
                saturated ? saturationDetail : null));
    }

    private ResidentProduction produceResident(ManagedCoopAncillaryRequest request,
                                                ResidentRecord resident,
                                                BlockAccess block) {
        int cycles = dueCycles(request, resident.residentSlot());
        if (cycles == 0) {
            return new ResidentProduction(false, 0, false);
        }
        String roleId = ManagedCoopAncillaryRequest.normalizeNullable(resident.roleId());
        String dropId = request.dropsByRole().get(roleId);
        int invocations = 0;
        boolean saturated = false;
        String detail = null;
        try {
            for (int cycle = 0; cycle < cycles && !saturated; cycle++) {
                for (int item = 0; item < request.itemsPerTick(); item++) {
                    InventoryApply apply;
                    try {
                        apply = runtime.addOne(block, dropId);
                    } catch (RuntimeException exception) {
                        apply = InventoryApply.possiblyPartial(
                                failureDetail("managed_coop_produce_inventory_apply", exception));
                    }
                    if (apply == null) {
                        apply = InventoryApply.possiblyPartial(
                                "managed_coop_produce_inventory_result_missing");
                    }
                    if (apply.status() != InventoryApplyStatus.APPLIED) {
                        saturated = true;
                        detail = apply.detail();
                        break;
                    }
                    invocations++;
                }
            }
        } finally {
            // Once a due attempt starts, consume its cadence even if inventory application may
            // have partially mutated before failing. Replaying that window could duplicate drops.
            recordProduction(request, resident.residentSlot());
        }
        return new ResidentProduction(true, invocations, saturated, detail);
    }

    @Nonnull
    private List<ResidentRecord> eligibleResidents(
            ManagedCoopAncillaryRequest request,
            ManagedCoopResidentIndex.Snapshot snapshot) {
        AuthorityRecord authority = snapshot.authority(request.authorityKey(), request.coopId());
        if (authority == null || authority.state() != AuthorityState.TWORK_MANAGED) {
            return List.of();
        }
        ArrayList<ResidentRecord> eligible = new ArrayList<>();
        HashSet<Integer> seenSlots = new HashSet<>();
        for (ResidentRecord resident : snapshot.residents(request.authorityKey())) {
            String roleId = ManagedCoopAncillaryRequest.normalizeNullable(resident.roleId());
            if (resident.active()
                    && resident.state() == ResidentState.HOUSED
                    && resident.coopId().equalsIgnoreCase(request.coopId())
                    && resident.residentSlot() >= 0
                    && resident.residentSlot() < request.maxResidents()
                    && roleId != null
                    && request.dropsByRole().containsKey(roleId)
                    && seenSlots.add(resident.residentSlot())) {
                eligible.add(resident);
            }
        }
        return List.copyOf(eligible);
    }

    private int dueCycles(ManagedCoopAncillaryRequest request, int residentSlot) {
        SlotCadenceKey key = new SlotCadenceKey(request.coopKey(), residentSlot);
        long now = request.gameTimeMs();
        Long lastValue = lastProducedAtBySlot.get(key);
        if (lastValue == null || now < lastValue) {
            return 1;
        }
        long last = lastValue;
        long intervalHours = Math.max(
                MINIMUM_PRODUCE_INTERVAL_HOURS, request.intervalGameHours());
        long elapsedHours = saturatedDifference(now, last) / GAME_MILLIS_PER_HOUR;
        if (elapsedHours < intervalHours) {
            return 0;
        }
        long cycles = elapsedHours / intervalHours;
        if (elapsedHours % intervalHours != 0L) {
            cycles++;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, cycles));
    }

    private void recordProduction(ManagedCoopAncillaryRequest request, int residentSlot) {
        lastProducedAtBySlot.put(
                new SlotCadenceKey(request.coopKey(), residentSlot), request.gameTimeMs());
    }

    private String interactionState(BlockAccess block) {
        return block.isEmpty() ? INTERACTION_STATE_EMPTY : INTERACTION_STATE_PRESENT;
    }

    private void emit(Outcome outcome) {
        try {
            outcomes.accept(outcome);
        } catch (RuntimeException ignored) {
            // Diagnostics cannot destabilize production or interaction-state updates.
        }
    }

    private Outcome outcome(OutcomeStatus status,
                            ManagedCoopAncillaryRequest request,
                            int residents,
                            int invocations,
                            @Nullable String detail) {
        return new Outcome(status, request.authorityKey(), residents, invocations, detail);
    }

    private static long saturatedDifference(long now, long previous) {
        try {
            return Math.subtractExact(now, previous);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static String failureDetail(String stage, RuntimeException exception) {
        String message = exception.getMessage();
        return stage + (message == null || message.isBlank()
                ? ":" + exception.getClass().getSimpleName() : ":" + message);
    }

    interface RuntimeGateway {
        boolean enqueue(@Nonnull String worldName, @Nonnull Runnable task);

        @Nullable
        BlockAccess resolve(@Nonnull ManagedCoopAncillaryRequest request);

        @Nonnull
        InventoryApply addOne(@Nonnull BlockAccess block, @Nonnull String dropReferenceId);
    }

    interface EpochGateway {
        @Nonnull
        Object lock();

        @Nullable
        CompositeEpoch capture();

        boolean isCurrent(@Nonnull CompositeEpoch epoch);
    }

    interface BlockAccess {
        boolean isEmpty();

        void setInteractionState(@Nonnull String state);
    }

    @FunctionalInterface
    public interface OutcomeSink {
        void accept(@Nonnull Outcome outcome);

        @Nonnull
        static OutcomeSink noop() {
            return ignored -> {
            };
        }
    }

    private record SlotCadenceKey(String coopKey, int residentSlot) {
    }

    private record ResidentProduction(boolean due,
                                      int invocations,
                                      boolean saturated,
                                      @Nullable String detail) {
        private ResidentProduction(boolean due, int invocations, boolean saturated) {
            this(due, invocations, saturated, null);
        }
    }

    private static final class CompositeEpochGateway implements EpochGateway {
        private final ManagedCoopResidentIndex residents;
        private final ManagedCoopLifecycleOperationIndex operations;
        private final ManagedCoopCompositeIndexRefreshService composite;

        private CompositeEpochGateway(ManagedCoopResidentIndex residents,
                                      ManagedCoopLifecycleOperationIndex operations,
                                      ManagedCoopCompositeIndexRefreshService composite) {
            this.residents = Objects.requireNonNull(residents, "residents");
            this.operations = Objects.requireNonNull(operations, "operations");
            this.composite = Objects.requireNonNull(composite, "composite");
        }

        @Override
        public Object lock() {
            return composite;
        }

        @Nullable
        @Override
        public CompositeEpoch capture() {
            if (!composite.isTrusted()) {
                return null;
            }
            CompositeEpoch epoch = new CompositeEpoch(
                    residents.snapshot().revision(), operations.snapshot().revision());
            return isCurrent(epoch) ? epoch : null;
        }

        @Override
        public boolean isCurrent(@Nonnull CompositeEpoch epoch) {
            return composite.isTrusted()
                    && residents.isTrusted()
                    && operations.isTrusted()
                    && residents.snapshot().revision() == epoch.residentRevision()
                    && operations.snapshot().revision() == epoch.operationRevision();
        }
    }
}
