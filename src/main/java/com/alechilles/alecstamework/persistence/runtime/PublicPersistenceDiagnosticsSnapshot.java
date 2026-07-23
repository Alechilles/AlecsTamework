package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceCircuitPolicy;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuit;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDomain;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureHookId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupReport;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Sanitized health snapshot derived from the one feature registry and durable
 * shared protocol evidence.
 */
public record PublicPersistenceDiagnosticsSnapshot(
        @Nonnull PersistenceStartupReport startup,
        @Nonnull Map<PersistenceFeatureId, FeatureHealth> features,
        long outboxHead,
        @Nonnull Map<ProjectionConsumerId, Long> projectionCheckpoints,
        @Nonnull Map<String, Long> openIncidentsByCode,
        @Nonnull Map<OperationScopeType, Long> activeQuarantinesByScope,
        @Nonnull Map<String, Long> activeQuarantinesByReason,
        @Nonnull PublicPersistenceMetricsSnapshot metrics
) {
    public PublicPersistenceDiagnosticsSnapshot {
        if (startup == null || features == null || features.isEmpty()
                || outboxHead < 0 || projectionCheckpoints == null
                || openIncidentsByCode == null
                || activeQuarantinesByScope == null
                || activeQuarantinesByReason == null || metrics == null) {
            throw new IllegalArgumentException(
                    "Complete public persistence diagnostics are required"
            );
        }
        features = Map.copyOf(features);
        projectionCheckpoints = copyCounts(projectionCheckpoints);
        openIncidentsByCode = copyCounts(openIncidentsByCode);
        activeQuarantinesByScope =
                copyCounts(activeQuarantinesByScope);
        activeQuarantinesByReason =
                copyCounts(activeQuarantinesByReason);
    }

    /** Complete descriptor-derived health for one persistence feature. */
    public record FeatureHealth(
            @Nonnull PersistenceFeatureId featureId,
            @Nonnull PersistenceFeatureDomain domain,
            @Nonnull PersistenceReadinessLevel readiness,
            @Nonnull PersistenceCircuitPolicy circuitPolicy,
            @Nonnull PersistenceFeatureCircuit circuit,
            @Nonnull Set<String> ownedAuthorities,
            @Nonnull Map<OperationKind, Set<OperationScopeType>>
                    operationScopes,
            @Nonnull Map<OperationKind, Map<OperationPhase, Long>>
                    operationCounts,
            @Nonnull Set<PersistenceFeatureId> startupDependencies,
            @Nonnull Set<ProjectionConsumerId> projectionConsumers,
            @Nonnull Set<PersistenceStartupNode> readinessEvidence,
            @Nonnull Set<OperationScopeType> quarantineGranularity,
            @Nonnull PersistenceFeatureHookId canonicalLoader,
            @Nonnull PersistenceFeatureHookId recoveryHandler,
            @Nonnull PersistenceFeatureHookId shutdownParticipant,
            @Nonnull PublicPersistenceMetricsSnapshot.FeatureMetrics metrics
    ) {
        public FeatureHealth {
            if (featureId == null || domain == null || readiness == null
                    || circuitPolicy == null || circuit == null
                    || !featureId.equals(circuit.featureId())
                    || ownedAuthorities == null || operationScopes == null
                    || operationCounts == null
                    || !operationScopes.keySet().equals(
                    operationCounts.keySet()
            ) || startupDependencies == null
                    || projectionConsumers == null
                    || readinessEvidence == null
                    || quarantineGranularity == null
                    || canonicalLoader == null || recoveryHandler == null
                    || shutdownParticipant == null || metrics == null) {
                throw new IllegalArgumentException(
                        "Complete descriptor feature health is required"
                );
            }
            ownedAuthorities = Set.copyOf(ownedAuthorities);
            operationScopes = copySets(operationScopes);
            operationCounts = copyNestedCounts(operationCounts);
            startupDependencies = Set.copyOf(startupDependencies);
            projectionConsumers = Set.copyOf(projectionConsumers);
            readinessEvidence = Set.copyOf(readinessEvidence);
            quarantineGranularity = Set.copyOf(quarantineGranularity);
        }
    }

    /** Returns complete operation totals aggregated across feature descriptors. */
    @Nonnull
    public Map<OperationPhase, Long> operationsByPhase() {
        EnumMap<OperationPhase, Long> totals =
                new EnumMap<>(OperationPhase.class);
        for (OperationPhase phase : OperationPhase.values()) {
            totals.put(phase, 0L);
        }
        features.values().forEach(feature ->
                feature.operationCounts().values().forEach(counts ->
                        counts.forEach((phase, count) ->
                                totals.merge(phase, count, Long::sum))));
        return Map.copyOf(totals);
    }

    /** Returns the number of descriptor circuits currently open. */
    public long openCircuitCount() {
        return features.values().stream()
                .filter(feature -> feature.circuit().state()
                        == com.alechilles.alecstamework.persistence.control
                        .PersistenceFeatureCircuitState.OPEN)
                .count();
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

    private static <K, V> Map<K, Set<V>> copySets(
            Map<K, Set<V>> source
    ) {
        HashMap<K, Set<V>> copied = new HashMap<>();
        source.forEach((key, values) -> {
            if (key == null || values == null) {
                throw new IllegalArgumentException(
                        "Diagnostic scope declarations are incomplete"
                );
            }
            copied.put(key, Set.copyOf(values));
        });
        return Map.copyOf(copied);
    }

    private static Map<OperationKind, Map<OperationPhase, Long>>
    copyNestedCounts(
            Map<OperationKind, Map<OperationPhase, Long>> source
    ) {
        HashMap<OperationKind, Map<OperationPhase, Long>> copied =
                new HashMap<>();
        source.forEach((kind, counts) ->
                copied.put(kind, copyCounts(counts)));
        return Map.copyOf(copied);
    }
}
