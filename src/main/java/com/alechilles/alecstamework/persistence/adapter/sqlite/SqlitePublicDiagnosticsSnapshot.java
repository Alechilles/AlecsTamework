package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuit;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/** Sanitized durable evidence used to build public persistence diagnostics. */
public record SqlitePublicDiagnosticsSnapshot(
        @Nonnull Map<OperationKind, Map<OperationPhase, Long>>
                operationCounts,
        long outboxHead,
        @Nonnull Map<ProjectionConsumerId, Long> projectionCheckpoints,
        @Nonnull Map<PersistenceFeatureId, PersistenceFeatureCircuit> circuits,
        @Nonnull Map<String, Long> openIncidentsByCode,
        @Nonnull Map<OperationScopeType, Long> activeQuarantinesByScope,
        @Nonnull Map<String, Long> activeQuarantinesByReason
) {
    public SqlitePublicDiagnosticsSnapshot {
        if (operationCounts == null || outboxHead < 0
                || projectionCheckpoints == null || circuits == null
                || circuits.isEmpty() || openIncidentsByCode == null
                || activeQuarantinesByScope == null
                || activeQuarantinesByReason == null) {
            throw new IllegalArgumentException(
                    "Complete public persistence diagnostics are required"
            );
        }
        operationCounts = copyNested(operationCounts);
        projectionCheckpoints = copyCounts(projectionCheckpoints);
        circuits = Map.copyOf(circuits);
        openIncidentsByCode = copyCounts(openIncidentsByCode);
        activeQuarantinesByScope =
                copyCounts(activeQuarantinesByScope);
        activeQuarantinesByReason =
                copyCounts(activeQuarantinesByReason);
    }

    private static <K> Map<K, Long> copyCounts(Map<K, Long> source) {
        HashMap<K, Long> copied = new HashMap<>();
        source.forEach((key, count) -> {
            if (key == null || count == null || count < 0) {
                throw new IllegalArgumentException(
                        "Diagnostic counters must be non-negative"
                );
            }
            copied.put(key, count);
        });
        return Map.copyOf(copied);
    }

    private static Map<OperationKind, Map<OperationPhase, Long>>
    copyNested(
            Map<OperationKind, Map<OperationPhase, Long>> source
    ) {
        HashMap<OperationKind, Map<OperationPhase, Long>> copied =
                new HashMap<>();
        source.forEach((kind, counts) -> {
            if (kind == null || counts == null) {
                throw new IllegalArgumentException(
                        "Diagnostic operation counters are incomplete"
                );
            }
            copied.put(kind, copyCounts(counts));
        });
        return Map.copyOf(copied);
    }
}
