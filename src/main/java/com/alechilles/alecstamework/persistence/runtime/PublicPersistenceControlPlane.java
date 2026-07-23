package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceContainmentListener;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceWriteRejection;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * One passive bridge from kernel outcomes and durable containment to the
 * registry-derived admission coordinator.
 */
final class PublicPersistenceControlPlane
        implements PersistenceKernelMetrics,
        PersistenceOperationAdmissionGate,
        PersistenceContainmentListener {
    private final PersistenceFeatureRegistry registry;
    private final Map<PersistenceFeatureId, FeatureCounters> features;
    private final LongAdder readsCompleted = new LongAdder();
    private final LongAdder readsFailed = new LongAdder();
    private final LongAdder checkpointFailures = new LongAdder();
    private final LongAdder shutdownTimeouts = new LongAdder();
    private final AtomicReference<String> lastGlobalFailure =
            new AtomicReference<>();
    private volatile PersistenceStartupCoordinator startup;

    PublicPersistenceControlPlane(PersistenceFeatureRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException(
                    "Control plane feature registry is required"
            );
        }
        this.registry = registry;
        HashMap<PersistenceFeatureId, FeatureCounters> counters =
                new HashMap<>();
        for (PersistenceFeatureDescriptor descriptor
                : registry.descriptors()) {
            counters.put(
                    descriptor.featureId(),
                    new FeatureCounters(descriptor.metricsNamespace())
            );
        }
        features = Map.copyOf(counters);
    }

    void bind(PersistenceStartupCoordinator startup) {
        if (startup == null || this.startup != null) {
            throw new IllegalStateException(
                    "Control plane startup binding is invalid"
            );
        }
        this.startup = startup;
    }

    @Override
    public void requireAdmission(
            OperationKind kind,
            String featureScope,
            List<OperationScope> participants
    ) {
        requireStartup().requireAdmission(
                kind, featureScope, participants
        );
    }

    @Override
    public void contained(
            List<OperationScope> scopes,
            String reasonCode
    ) {
        requireStartup().contained(scopes, reasonCode);
    }

    @Override
    public void writeAccepted(
            OperationId operationId,
            OperationKind operationKind
    ) {
        FeatureCounters counters = counters(operationKind);
        if (counters != null) {
            counters.accepted.increment();
        }
    }

    @Override
    public void writeRejected(
            OperationId operationId,
            OperationKind operationKind,
            PersistenceWriteRejection reason
    ) {
        FeatureCounters counters = counters(operationKind);
        if (counters != null) {
            counters.rejected.increment();
        }
    }

    @Override
    public void busyRetry(
            OperationId operationId,
            OperationKind operationKind,
            int retryNumber
    ) {
        FeatureCounters counters = counters(operationKind);
        if (counters != null) {
            counters.busyRetries.increment();
        }
    }

    @Override
    public void unitOfWorkCompleted(
            OperationKind operationKind,
            PersistenceTransactionResult<?> result
    ) {
        FeatureCounters counters = counters(operationKind);
        if (counters != null) {
            counters.completed.increment();
            if (!(result instanceof
                    PersistenceTransactionResult.Committed<?>)) {
                counters.failed.increment();
            }
        }
        if (result instanceof PersistenceTransactionResult.Unknown<?> unknown) {
            enterGlobal(unknown.failure());
        } else if (result instanceof
                PersistenceTransactionResult.RolledBack<?> rolledBack
                && globalFailure(rolledBack.failure())) {
            enterGlobal(rolledBack.failure());
        }
    }

    @Override
    public void readCompleted(
            PersistenceReadKind readKind,
            PersistenceReadResult<?> result
    ) {
        readsCompleted.increment();
        if (result instanceof PersistenceReadResult.Failed<?> failed) {
            readsFailed.increment();
            if (globalFailure(failed.failure())) {
                enterGlobal(failed.failure());
            }
        }
    }

    @Override
    public void checkpointFailure(
            String checkpoint,
            Throwable failure
    ) {
        checkpointFailures.increment();
        String normalized = checkpoint == null || checkpoint.isBlank()
                ? "unknown"
                : checkpoint.trim().toLowerCase(java.util.Locale.ROOT);
        enterGlobal("checkpoint_failure:" + normalized);
    }

    @Override
    public void shutdownTimedOut(int outstandingOperations) {
        shutdownTimeouts.increment();
    }

    PublicPersistenceMetricsSnapshot snapshot() {
        HashMap<PersistenceFeatureId,
                PublicPersistenceMetricsSnapshot.FeatureMetrics> result =
                new HashMap<>();
        features.forEach((featureId, counters) ->
                result.put(featureId, counters.snapshot()));
        return new PublicPersistenceMetricsSnapshot(
                readsCompleted.sum(),
                readsFailed.sum(),
                checkpointFailures.sum(),
                shutdownTimeouts.sum(),
                lastGlobalFailure.get(),
                result
        );
    }

    private FeatureCounters counters(OperationKind operationKind) {
        try {
            PersistenceFeatureDescriptor descriptor =
                    registry.requireOperation(operationKind);
            return features.get(descriptor.featureId());
        } catch (IllegalArgumentException internalOperation) {
            return null;
        }
    }

    private boolean globalFailure(StorageFailure failure) {
        if (failure.retryable()
                || "unknown_commit_proven_absent".equals(failure.code())) {
            return false;
        }
        return switch (failure.kind()) {
            case IO, CORRUPT, SCHEMA, UNAVAILABLE, DECODE, UNKNOWN -> true;
            case BUSY, TIMEOUT -> false;
        };
    }

    private void enterGlobal(StorageFailure failure) {
        enterGlobal(failure.code());
    }

    private void enterGlobal(String code) {
        String normalized = code == null || code.isBlank()
                ? "unbounded_storage_failure"
                : code.trim();
        lastGlobalFailure.compareAndSet(null, normalized);
        PersistenceStartupCoordinator current = startup;
        if (current != null) {
            current.enterGlobalReadOnly(normalized);
        }
    }

    private PersistenceStartupCoordinator requireStartup() {
        PersistenceStartupCoordinator current = startup;
        if (current == null) {
            throw new IllegalStateException(
                    "Control plane startup is not bound"
            );
        }
        return current;
    }

    private static final class FeatureCounters {
        private final String namespace;
        private final LongAdder accepted = new LongAdder();
        private final LongAdder rejected = new LongAdder();
        private final LongAdder busyRetries = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder failed = new LongAdder();

        private FeatureCounters(String namespace) {
            this.namespace = namespace;
        }

        private PublicPersistenceMetricsSnapshot.FeatureMetrics snapshot() {
            return new PublicPersistenceMetricsSnapshot.FeatureMetrics(
                    namespace,
                    accepted.sum(),
                    rejected.sum(),
                    busyRetries.sum(),
                    completed.sum(),
                    failed.sum()
            );
        }
    }
}
