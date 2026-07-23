package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicDiagnosticsSnapshot;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Joins durable evidence with the registry-owned live control-plane view. */
final class PublicPersistenceDiagnosticsAssembler {
    private final PersistenceFeatureRegistry registry;
    private final PersistenceStartupCoordinator startup;
    private final PublicPersistenceControlPlane control;

    PublicPersistenceDiagnosticsAssembler(
            PersistenceFeatureRegistry registry,
            PersistenceStartupCoordinator startup,
            PublicPersistenceControlPlane control
    ) {
        if (registry == null || startup == null || control == null) {
            throw new IllegalArgumentException(
                    "Diagnostic assembler dependencies are required"
            );
        }
        this.registry = registry;
        this.startup = startup;
        this.control = control;
    }

    PersistenceReadResult<PublicPersistenceDiagnosticsSnapshot> assemble(
            PersistenceReadResult<SqlitePublicDiagnosticsSnapshot> result
    ) {
        if (result instanceof
                PersistenceReadResult.Found<
                        SqlitePublicDiagnosticsSnapshot> found) {
            return PersistenceReadResult.found(
                    build(found.value()),
                    found.revision()
            );
        }
        if (result instanceof PersistenceReadResult.Failed<?> failed) {
            return PersistenceReadResult.failed(failed.failure());
        }
        return PersistenceReadResult.absent();
    }

    private PublicPersistenceDiagnosticsSnapshot build(
            SqlitePublicDiagnosticsSnapshot durable
    ) {
        PublicPersistenceMetricsSnapshot metrics = control.snapshot();
        HashMap<PersistenceFeatureId,
                PublicPersistenceDiagnosticsSnapshot.FeatureHealth> features =
                new HashMap<>();
        for (var descriptor : registry.descriptors()) {
            features.put(
                    descriptor.featureId(),
                    new PublicPersistenceDiagnosticsSnapshot.FeatureHealth(
                            descriptor.featureId(),
                            descriptor.domain(),
                            startup.readiness(descriptor.featureId()),
                            descriptor.circuitPolicy(),
                            durable.circuits().get(descriptor.featureId()),
                            descriptor.ownedAuthorities(),
                            descriptor.operationScopes(),
                            operationCounts(descriptor, durable),
                            descriptor.startupDependencies(),
                            descriptor.projectionConsumers(),
                            descriptor.readinessEvidence(),
                            descriptor.quarantineGranularity(),
                            descriptor.canonicalLoader(),
                            descriptor.recoveryHandler(),
                            descriptor.shutdownParticipant(),
                            metrics.features().get(descriptor.featureId())
                    )
            );
        }
        return new PublicPersistenceDiagnosticsSnapshot(
                startup.report(),
                features,
                durable.outboxHead(),
                durable.projectionCheckpoints(),
                durable.openIncidentsByCode(),
                durable.activeQuarantinesByScope(),
                durable.activeQuarantinesByReason(),
                metrics
        );
    }

    private Map<OperationKind, Map<OperationPhase, Long>> operationCounts(
            PersistenceFeatureDescriptor descriptor,
            SqlitePublicDiagnosticsSnapshot durable
    ) {
        HashMap<OperationKind, Map<OperationPhase, Long>> result =
                new HashMap<>();
        for (var definition : descriptor.operationDefinitions()) {
            EnumMap<OperationPhase, Long> phases =
                    new EnumMap<>(OperationPhase.class);
            for (OperationPhase phase : OperationPhase.values()) {
                phases.put(
                        phase,
                        durable.operationCounts()
                                .getOrDefault(definition.kind(), Map.of())
                                .getOrDefault(phase, 0L)
                );
            }
            result.put(definition.kind(), Map.copyOf(phases));
        }
        return Map.copyOf(result);
    }
}
