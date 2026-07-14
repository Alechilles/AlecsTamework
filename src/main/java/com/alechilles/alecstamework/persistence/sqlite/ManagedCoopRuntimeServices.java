package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.ManagedCoopLifecycleOperationIndex;
import com.alechilles.alecstamework.items.ManagedCoopLifecycleOperationIndexRefreshService;
import com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService;
import com.alechilles.alecstamework.items.ManagedCoopOccupancyService;
import com.alechilles.alecstamework.items.ManagedCoopResidentIndex;
import com.alechilles.alecstamework.items.ManagedCoopResidentIndexRefreshService;
import com.hypixel.hytale.logger.HytaleLogger;
import java.sql.SQLException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns the focused persistence and immutable runtime-index services for managed coops. */
public final class ManagedCoopRuntimeServices {
    private final ManagedCoopResidentRepository residentRepository;
    private final CoopLifecycleOperationRepository lifecycleRepository;
    private final ManagedCoopCaptureProfileRepository captureProfileRepository;
    private final ManagedCoopImportRepository importRepository;
    private final ManagedCoopResidentIndex residentIndex;
    private final ManagedCoopResidentIndexRefreshService residentIndexRefreshService;
    private final ManagedCoopLifecycleOperationIndex lifecycleIndex;
    private final ManagedCoopLifecycleOperationIndexRefreshService lifecycleIndexRefreshService;
    private final ManagedCoopCompositeIndexRefreshService compositeIndexRefreshService;
    private final ManagedCoopOccupancyService occupancyService;
    private final ManagedCoopStaleResidentReconciler staleResidentReconciler;

    ManagedCoopRuntimeServices(@Nonnull SqliteConnectionManager connectionManager,
                               @Nonnull PersistenceWriteQueue writeQueue,
                               @Nonnull NpcProfileRepository profileRepository,
                               @Nullable HytaleLogger logger) {
        residentRepository = new ManagedCoopResidentRepository(connectionManager, writeQueue);
        lifecycleRepository = new CoopLifecycleOperationRepository(
                connectionManager,
                writeQueue,
                residentRepository
        );
        captureProfileRepository = new ManagedCoopCaptureProfileRepository(writeQueue, profileRepository);
        importRepository = new ManagedCoopImportRepository(connectionManager, writeQueue);
        residentIndex = new ManagedCoopResidentIndex();
        residentIndexRefreshService = new ManagedCoopResidentIndexRefreshService(
                residentRepository,
                residentIndex,
                logger
        );
        lifecycleIndex = new ManagedCoopLifecycleOperationIndex();
        lifecycleIndexRefreshService = new ManagedCoopLifecycleOperationIndexRefreshService(
                lifecycleRepository,
                lifecycleIndex,
                logger
        );
        compositeIndexRefreshService = new ManagedCoopCompositeIndexRefreshService(
                residentIndexRefreshService,
                lifecycleIndexRefreshService,
                residentIndex,
                lifecycleIndex
        );
        occupancyService = new ManagedCoopOccupancyService(
                residentIndex,
                compositeIndexRefreshService::isTrusted
        );
        staleResidentReconciler = new ManagedCoopStaleResidentReconciler(
                connectionManager,
                residentRepository
        );
    }

    @Nonnull
    public ManagedCoopResidentRepository residentRepository() {
        return residentRepository;
    }

    @Nonnull
    public CoopLifecycleOperationRepository lifecycleRepository() {
        return lifecycleRepository;
    }

    @Nonnull
    public ManagedCoopCaptureProfileRepository captureProfileRepository() {
        return captureProfileRepository;
    }

    @Nonnull
    public ManagedCoopImportRepository importRepository() {
        return importRepository;
    }

    @Nonnull
    public ManagedCoopResidentIndex residentIndex() {
        return residentIndex;
    }

    @Nonnull
    public ManagedCoopResidentIndexRefreshService residentIndexRefreshService() {
        return residentIndexRefreshService;
    }

    @Nonnull
    public ManagedCoopLifecycleOperationIndex lifecycleIndex() {
        return lifecycleIndex;
    }

    @Nonnull
    public ManagedCoopLifecycleOperationIndexRefreshService lifecycleIndexRefreshService() {
        return lifecycleIndexRefreshService;
    }

    @Nonnull
    public ManagedCoopCompositeIndexRefreshService compositeIndexRefreshService() {
        return compositeIndexRefreshService;
    }

    @Nonnull
    public ManagedCoopOccupancyService occupancyService() {
        return occupancyService;
    }

    @Nonnull
    ManagedCoopStaleResidentReconciler.RepairResult reconcileStaleResidents()
            throws SQLException {
        return staleResidentReconciler.reconcile();
    }
}
