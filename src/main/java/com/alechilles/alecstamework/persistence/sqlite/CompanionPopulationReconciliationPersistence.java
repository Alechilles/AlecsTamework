package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Owns the repositories used only by startup and live population reconciliation. */
public final class CompanionPopulationReconciliationPersistence {
    private final CompanionPopulationReconciliationRepository reconciliationRepository;
    private final CompanionPopulationRepairRepository repairRepository;
    private final CompanionPopulationLegacyEvidenceRepository legacyEvidenceRepository;
    private final CompanionPopulationObservationRepository observationRepository;
    private final CompanionPopulationScanSessionRepository scanSessionRepository;
    private final CompanionPersistedProjectionEvidenceRegistry projectionEvidenceRegistry;

    public CompanionPopulationReconciliationPersistence(
            @Nonnull SqliteConnectionManager connectionManager,
            @Nonnull PersistenceWriteQueue writeQueue
    ) {
        Objects.requireNonNull(connectionManager, "connectionManager");
        Objects.requireNonNull(writeQueue, "writeQueue");
        this.reconciliationRepository =
                new CompanionPopulationReconciliationRepository(connectionManager, writeQueue);
        this.repairRepository = new CompanionPopulationRepairRepository(writeQueue);
        this.legacyEvidenceRepository = new CompanionPopulationLegacyEvidenceRepository(connectionManager);
        this.observationRepository = new CompanionPopulationObservationRepository(writeQueue);
        this.scanSessionRepository = new CompanionPopulationScanSessionRepository(connectionManager, writeQueue);
        this.projectionEvidenceRegistry = new CompanionPersistedProjectionEvidenceRegistry();
    }

    @Nonnull
    public CompanionPopulationReconciliationRepository reconciliationRepository() {
        return reconciliationRepository;
    }

    @Nonnull
    public CompanionPopulationRepairRepository repairRepository() {
        return repairRepository;
    }

    @Nonnull
    public CompanionPopulationLegacyEvidenceRepository legacyEvidenceRepository() {
        return legacyEvidenceRepository;
    }

    @Nonnull
    public CompanionPopulationObservationRepository observationRepository() {
        return observationRepository;
    }

    @Nonnull
    public CompanionPopulationScanSessionRepository scanSessionRepository() {
        return scanSessionRepository;
    }

    @Nonnull
    public CompanionPersistedProjectionEvidenceRegistry projectionEvidenceRegistry() {
        return projectionEvidenceRegistry;
    }
}
