package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpointHook;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Combines storage, scoped quarantine, feature circuits, and exact evidence requirements. */
public final class PersistenceMutationAvailabilityService {
    private final PersistenceStorageHealthService storageHealth;
    private final PersistenceQuarantineRegistry quarantines;
    private final PersistenceFeatureCircuitRegistry circuits;
    private final PersistenceCoverageReadiness coverage;
    private final PersistenceCheckpointHook checkpoints;

    public PersistenceMutationAvailabilityService(@Nonnull PersistenceStorageHealthService storageHealth,
                                                  @Nonnull PersistenceQuarantineRegistry quarantines,
                                                  @Nonnull PersistenceFeatureCircuitRegistry circuits,
                                                  @Nonnull PersistenceCoverageReadiness coverage) {
        this(storageHealth, quarantines, circuits, coverage, PersistenceCheckpointHook.NO_OP);
    }

    PersistenceMutationAvailabilityService(@Nonnull PersistenceStorageHealthService storageHealth,
                                            @Nonnull PersistenceQuarantineRegistry quarantines,
                                            @Nonnull PersistenceFeatureCircuitRegistry circuits,
                                            @Nonnull PersistenceCoverageReadiness coverage,
                                            @Nonnull PersistenceCheckpointHook checkpoints) {
        this.storageHealth = storageHealth;
        this.quarantines = quarantines;
        this.circuits = circuits;
        this.coverage = coverage;
        this.checkpoints = checkpoints;
    }

    @Nonnull
    public PersistenceMutationAvailabilityDecision decide(@Nonnull PersistenceMutationContext context) {
        try {
            checkpoints.hit(PersistenceCheckpoint.BEFORE_AVAILABILITY_ADMISSION, null);
        } catch (Exception failure) {
            return deny(PersistenceMutationAvailabilityStatus.RETRYABLE_DENIAL,
                    "availability_checkpoint_failed", null);
        }
        if (!storageHealth.acceptsWrites()) {
            return deny(PersistenceMutationAvailabilityStatus.GLOBAL_READ_ONLY,
                    storageHealth.getState().reason(), storageHealth.getState().incidentId());
        }
        Optional<PersistenceQuarantineRecord> quarantine = findQuarantine(context);
        if (quarantine.isPresent()) {
            PersistenceQuarantineRecord record = quarantine.orElseThrow();
            return deny(PersistenceMutationAvailabilityStatus.QUARANTINED,
                    record.reasonCode(), record.incidentId());
        }
        if (!circuits.isEnabled(context.domain())) {
            return deny(PersistenceMutationAvailabilityStatus.FEATURE_PAUSED,
                    "feature_paused_by_operator", null);
        }
        if (!coverage.areReady(context.requiredCoverageDimensions(), context.scopes())) {
            return deny(PersistenceMutationAvailabilityStatus.AUTHORITY_NOT_READY,
                    "required_evidence_coverage_unavailable", null);
        }
        return new PersistenceMutationAvailabilityDecision(
                PersistenceMutationAvailabilityStatus.ALLOW, "allowed", null);
    }

    private Optional<PersistenceQuarantineRecord> findQuarantine(PersistenceMutationContext context) {
        Optional<PersistenceQuarantineRecord> global = quarantines.find(PersistenceScopeType.GLOBAL, "*");
        if (global.isPresent()) return global;
        Optional<PersistenceQuarantineRecord> domain = quarantines.find(
                PersistenceScopeType.FEATURE_DOMAIN, context.domain().name());
        if (domain.isPresent()) return domain;
        for (var scope : context.scopes()) {
            Optional<PersistenceQuarantineRecord> exact = quarantines.find(scope);
            if (exact.isPresent()) return exact;
        }
        return Optional.empty();
    }

    private PersistenceMutationAvailabilityDecision deny(PersistenceMutationAvailabilityStatus status,
                                                          String reason, String incidentId) {
        String normalized = reason == null || reason.isBlank() ? status.name().toLowerCase() : reason;
        return new PersistenceMutationAvailabilityDecision(status, normalized, incidentId);
    }
}
