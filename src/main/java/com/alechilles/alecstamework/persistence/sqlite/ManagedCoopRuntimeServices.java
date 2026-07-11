package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.ManagedCoopResidentIndex;
import com.alechilles.alecstamework.items.ManagedCoopResidentIndexRefreshService;
import com.hypixel.hytale.logger.HytaleLogger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns the focused persistence and immutable runtime-index services for managed coops. */
public final class ManagedCoopRuntimeServices {
    private final ManagedCoopResidentRepository residentRepository;
    private final CoopLifecycleOperationRepository lifecycleRepository;
    private final ManagedCoopCaptureProfileRepository captureProfileRepository;
    private final ManagedCoopResidentIndex residentIndex;
    private final ManagedCoopResidentIndexRefreshService residentIndexRefreshService;

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
        residentIndex = new ManagedCoopResidentIndex();
        residentIndexRefreshService = new ManagedCoopResidentIndexRefreshService(
                residentRepository,
                residentIndex,
                logger
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
    public ManagedCoopResidentIndex residentIndex() {
        return residentIndex;
    }

    @Nonnull
    public ManagedCoopResidentIndexRefreshService residentIndexRefreshService() {
        return residentIndexRefreshService;
    }
}
