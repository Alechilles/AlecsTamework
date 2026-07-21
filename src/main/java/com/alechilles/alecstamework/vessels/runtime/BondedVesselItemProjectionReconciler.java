package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselBindingInvalidatedEvent;
import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.ownership.reconciliation.BondedVesselInventoryEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.vessels.BondedVesselEventSink;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reconciles bonded item presence only after all bounded persisted inventory sources are sealed.
 * This observer never changes vessel lifecycle or generation.
 */
public final class BondedVesselItemProjectionReconciler {
    private final ProjectionStore store;
    private final BondedVesselEventSink events;
    private final Executor executor;
    private final LongSupplier clockMs;
    private final BondedVesselItemFingerprintCodec fingerprints =
            new BondedVesselItemFingerprintCodec();

    public BondedVesselItemProjectionReconciler(
            @Nonnull BondedVesselRepository repository,
            @Nullable BondedVesselEventSink events,
            @Nonnull Executor executor,
            @Nonnull LongSupplier clockMs
    ) {
        this(new RepositoryProjectionStore(repository), events, executor, clockMs);
    }

    BondedVesselItemProjectionReconciler(
            @Nonnull ProjectionStore store,
            @Nullable BondedVesselEventSink events,
            @Nonnull Executor executor,
            @Nonnull LongSupplier clockMs
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = events == null ? BondedVesselEventSink.NO_OP : events;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
    }

    /** Incomplete or invalidated coverage is UNKNOWN and never authorizes a durable downgrade. */
    @Nonnull
    public CompletionStage<Report> reconcileSealed(
            @Nonnull CompanionPersistedProjectionEvidenceRegistry.Snapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.sealed()) {
            return CompletableFuture.completedFuture(
                    new Report(Status.UNKNOWN, 0, 0, 0, 0,
                            "bonded_vessel_inventory_evidence_unsealed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.loadNonReleasedBindings();
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, executor).thenCompose(bindings -> reconcileNext(snapshot, bindings, 0,
                new MutableReport()));
    }

    @Nonnull
    private CompletionStage<Report> reconcileNext(
            @Nonnull CompanionPersistedProjectionEvidenceRegistry.Snapshot snapshot,
            @Nonnull List<BondedVesselBindingRecord> bindings,
            int index,
            @Nonnull MutableReport report
    ) {
        if (index >= bindings.size()) {
            return CompletableFuture.completedFuture(report.finish());
        }
        BondedVesselBindingRecord binding = bindings.get(index);
        if (binding.lifecycleState() != BondedVesselBindingRecord.LifecycleState.ACTIVE
                && binding.lifecycleState() != BondedVesselBindingRecord.LifecycleState.STORED) {
            return reconcileNext(snapshot, bindings, index + 1, report);
        }
        Classification classification = classify(snapshot, binding);
        if (classification.status() == BondedVesselProjectionStatus.UNKNOWN) {
            report.unknown++;
            return reconcileNext(snapshot, bindings, index + 1, report);
        }
        return store.update(binding, classification.status(), classification.reason(), clockMs.getAsLong())
                .handle((result, failure) -> {
                    if (failure != null || result == null) {
                        report.unknown++;
                        return null;
                    }
                    switch (result.status()) {
                        case CHANGED -> {
                            report.changed++;
                            if (classification.status() == BondedVesselProjectionStatus.MISSING) {
                                report.missing++;
                                emitInvalidated(binding, classification, snapshot.revision());
                            } else if (classification.status()
                                    == BondedVesselProjectionStatus.AMBIGUOUS) {
                                report.ambiguous++;
                                emitInvalidated(binding, classification, snapshot.revision());
                            }
                        }
                        case IDEMPOTENT -> report.unchanged++;
                        case CONFLICT, FAILED -> report.unknown++;
                    }
                    return null;
                }).thenCompose(ignored -> reconcileNext(
                        snapshot, bindings, index + 1, report));
    }

    @Nonnull
    private Classification classify(
            @Nonnull CompanionPersistedProjectionEvidenceRegistry.Snapshot snapshot,
            @Nonnull BondedVesselBindingRecord binding
    ) {
        if ((binding.lifecycleState() != BondedVesselBindingRecord.LifecycleState.ACTIVE
                && binding.lifecycleState() != BondedVesselBindingRecord.LifecycleState.STORED)
                || binding.activeOperationId() != null
                || binding.lastItemId() == null) {
            return Classification.unknown();
        }
        final UUID bindingId;
        final String expected;
        try {
            bindingId = UUID.fromString(binding.bindingId());
            expected = fingerprints.fingerprint(new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                    binding.lastItemId(), bindingId, binding.profileId(), binding.generation(),
                    binding.configId(), BondedVesselState.valueOf(binding.lifecycleState().name())));
        } catch (RuntimeException failure) {
            return Classification.unknown();
        }
        List<BondedVesselInventoryEvidence.Observation> observations =
                snapshot.evidenceSet().bondedVesselItemObservations(bindingId);
        if (observations.isEmpty()) {
            return new Classification(
                    BondedVesselProjectionStatus.MISSING,
                    "bonded_vessel_item_missing_after_sealed_inventory_scan");
        }
        if (observations.size() == 1
                && observations.getFirst().generation() == binding.generation()
                && expected.equals(observations.getFirst().fingerprint())) {
            return new Classification(BondedVesselProjectionStatus.PRESENT, null);
        }
        return new Classification(
                BondedVesselProjectionStatus.AMBIGUOUS,
                "bonded_vessel_item_ambiguous_after_sealed_inventory_scan");
    }

    private void emitInvalidated(
            BondedVesselBindingRecord binding,
            Classification classification,
            long evidenceRevision
    ) {
        long now = clockMs.getAsLong();
        try {
            UUID bindingId = UUID.fromString(binding.bindingId());
            String identity = "bonded-vessel-projection-reconciliation-v1|"
                    + binding.bindingId() + "|" + binding.generation() + "|"
                    + evidenceRevision + "|" + classification.status().name();
            events.emit(new BondedVesselBindingInvalidatedEvent(
                    UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                    bindingId,
                    binding.profileId(),
                    binding.ownerUuid(),
                    binding.configId(),
                    binding.generation(),
                    binding.generation(),
                    BondedVesselState.valueOf(binding.lifecycleState().name()),
                    classification.status(),
                    Objects.requireNonNull(classification.reason(), "reason"),
                    true,
                    now,
                    now
            ));
        } catch (RuntimeException ignored) {
            // Event delivery is diagnostic and cannot roll back a committed projection update.
        }
    }

    public enum Status { RECONCILED, UNKNOWN }

    public record Report(@Nonnull Status status, int changed, int unchanged,
                         int missing, int ambiguous, @Nullable String reason) { }

    enum UpdateStatus { CHANGED, IDEMPOTENT, CONFLICT, FAILED }

    record UpdateResult(@Nonnull UpdateStatus status) { }

    interface ProjectionStore {
        List<BondedVesselBindingRecord> loadNonReleasedBindings() throws Exception;

        CompletionStage<UpdateResult> update(
                BondedVesselBindingRecord binding,
                BondedVesselProjectionStatus status,
                @Nullable String reason,
                long nowMs);
    }

    private static final class RepositoryProjectionStore implements ProjectionStore {
        private final BondedVesselRepository repository;

        private RepositoryProjectionStore(BondedVesselRepository repository) {
            this.repository = Objects.requireNonNull(repository, "repository");
        }

        @Override
        public List<BondedVesselBindingRecord> loadNonReleasedBindings() throws Exception {
            return repository.loadNonReleasedBindings();
        }

        @Override
        public CompletionStage<UpdateResult> update(
                BondedVesselBindingRecord binding,
                BondedVesselProjectionStatus status,
                @Nullable String reason,
                long nowMs
        ) {
            BondedVesselBindingRecord.ItemProjectionStatus target =
                    BondedVesselBindingRecord.ItemProjectionStatus.valueOf(status.name());
            PersistenceWriteQueue.WriteSubmission<BondedVesselRepository.MutationResult> submission =
                    repository.reconcileItemProjectionAsync(
                            binding.bindingId(), binding.generation(), binding.lifecycleState(),
                            target, reason, nowMs);
            return submission.completion().thenApply(outcome -> {
                if (!outcome.isCommitted() || outcome.value() == null) {
                    return new UpdateResult(UpdateStatus.FAILED);
                }
                return new UpdateResult(switch (outcome.value().status()) {
                    case APPLIED -> UpdateStatus.CHANGED;
                    case IDEMPOTENT -> UpdateStatus.IDEMPOTENT;
                    case CONFLICT, INVALID_STATE, NOT_FOUND -> UpdateStatus.CONFLICT;
                    default -> UpdateStatus.FAILED;
                });
            });
        }
    }

    private record Classification(@Nonnull BondedVesselProjectionStatus status,
                                  @Nullable String reason) {
        private static Classification unknown() {
            return new Classification(BondedVesselProjectionStatus.UNKNOWN, null);
        }
    }

    private static final class MutableReport {
        private int changed;
        private int unchanged;
        private int missing;
        private int ambiguous;
        private int unknown;

        private Report finish() {
            return new Report(
                    unknown > 0 ? Status.UNKNOWN : Status.RECONCILED,
                    changed, unchanged, missing, ambiguous,
                    unknown > 0 ? "bonded_vessel_projection_reconciliation_incomplete" : null);
        }
    }
}
