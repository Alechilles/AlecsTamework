package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityService;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentSink;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.recovery.ScopedPersistenceRecoveryCoordinator;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Composes and bootstraps v7 incident, quarantine, circuit, and availability collaborators. */
public final class PersistenceResilienceRuntime {
    private final PersistenceIncidentRepository incidents;
    private final PersistenceQuarantineRepository quarantineRepository;
    private final PersistenceQuarantineRegistry quarantines;
    private final PersistenceFeatureCircuitRepository circuitRepository;
    private final PersistenceFeatureCircuitRegistry circuits;
    private final PersistenceIncidentReporter reporter;
    private final PersistenceMutationAvailabilityService availability;
    private final ScopedPersistenceRecoveryCoordinator scopedRecovery;

    private PersistenceResilienceRuntime(PersistenceIncidentRepository incidents,
                                         PersistenceQuarantineRepository quarantineRepository,
                                         PersistenceQuarantineRegistry quarantines,
                                         PersistenceFeatureCircuitRepository circuitRepository,
                                         PersistenceFeatureCircuitRegistry circuits,
                                         PersistenceIncidentReporter reporter,
                                         PersistenceMutationAvailabilityService availability,
                                         ScopedPersistenceRecoveryCoordinator scopedRecovery) {
        this.incidents = incidents;
        this.quarantineRepository = quarantineRepository;
        this.quarantines = quarantines;
        this.circuitRepository = circuitRepository;
        this.circuits = circuits;
        this.reporter = reporter;
        this.availability = availability;
        this.scopedRecovery = scopedRecovery;
    }

    @Nonnull
    public static PersistenceResilienceRuntime initialize(
            @Nonnull String bootId,
            @Nonnull SqliteConnectionManager connections,
            @Nonnull PersistenceWriteQueue writeQueue,
            @Nonnull PersistenceStorageHealthService storageHealth,
            @Nullable HytaleLogger logger) {
        return initialize(bootId, connections, writeQueue, storageHealth,
                logger, PersistenceIncidentSink.NO_OP);
    }

    @Nonnull
    public static PersistenceResilienceRuntime initialize(
            @Nonnull String bootId,
            @Nonnull SqliteConnectionManager connections,
            @Nonnull PersistenceWriteQueue writeQueue,
            @Nonnull PersistenceStorageHealthService storageHealth,
            @Nullable HytaleLogger logger,
            @Nonnull PersistenceIncidentSink incidentSink) {
        PersistenceIncidentRepository incidents = new PersistenceIncidentRepository(connections);
        PersistenceQuarantineRepository quarantineRepository =
                new PersistenceQuarantineRepository(connections);
        PersistenceQuarantineRegistry quarantines = new PersistenceQuarantineRegistry();
        PersistenceFeatureCircuitRegistry circuits = new PersistenceFeatureCircuitRegistry();
        PersistenceFeatureCircuitRepository circuitRepository =
                new PersistenceFeatureCircuitRepository(connections, writeQueue);
        loadDurableDenials(storageHealth, quarantineRepository, quarantines,
                circuitRepository, circuits, logger);
        PersistenceIncidentReporter reporter = new PersistenceIncidentReporter(
                bootId, new PersistenceFailureClassifier(), incidents, quarantineRepository,
                quarantines, storageHealth, writeQueue, incidentSink);
        writeQueue.setFailureHandler(new PersistenceIncidentWriteFailureHandler(reporter));
        PersistenceMutationAvailabilityService availability =
                new PersistenceMutationAvailabilityService(
                        storageHealth, quarantines, circuits, ignored -> true);
        ScopedPersistenceRecoveryCoordinator scopedRecovery =
                new ScopedPersistenceRecoveryCoordinator(
                        incidents, quarantineRepository, quarantines, circuits, writeQueue, incidentSink);
        return new PersistenceResilienceRuntime(
                incidents, quarantineRepository, quarantines, circuitRepository,
                circuits, reporter, availability, scopedRecovery);
    }

    private static void loadDurableDenials(
            PersistenceStorageHealthService storageHealth,
            PersistenceQuarantineRepository quarantineRepository,
            PersistenceQuarantineRegistry quarantines,
            PersistenceFeatureCircuitRepository circuitRepository,
            PersistenceFeatureCircuitRegistry circuits,
            HytaleLogger logger) {
        if (!storageHealth.acceptsWrites()) return;
        try {
            quarantines.reload(quarantineRepository.listActive());
            circuits.reload(circuitRepository.load());
        } catch (Exception exception) {
            storageHealth.enterReadOnly("persistence_v7_registry_load_failed", null);
            if (logger != null) {
                logger.at(Level.SEVERE).log(
                        "Persistence safety registry load failed: " + exception.getMessage());
            }
        }
    }

    @Nonnull
    public PersistenceIncidentRepository incidents() {
        return incidents;
    }

    @Nonnull
    public PersistenceQuarantineRepository quarantineRepository() {
        return quarantineRepository;
    }

    @Nonnull
    public PersistenceQuarantineRegistry quarantines() {
        return quarantines;
    }

    @Nonnull
    public PersistenceFeatureCircuitRepository circuitRepository() {
        return circuitRepository;
    }

    @Nonnull
    public PersistenceFeatureCircuitRegistry circuits() {
        return circuits;
    }

    @Nonnull
    public PersistenceIncidentReporter reporter() {
        return reporter;
    }

    @Nonnull
    public PersistenceMutationAvailabilityService availability() {
        return availability;
    }

    @Nonnull
    public ScopedPersistenceRecoveryCoordinator scopedRecovery() {
        return scopedRecovery;
    }

    public void close() {
        scopedRecovery.close();
    }
}
