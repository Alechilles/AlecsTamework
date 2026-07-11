package com.alechilles.alecstamework.items;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Installs the planned UUID and complete durable state in the pre-add spawn phase. */
final class PlannedNpcProjectionSpawnPlanner implements PlannedNpcProjectionSpawner.SpawnPlanner {
    private final CoopResidentStateRestorer restorer;

    PlannedNpcProjectionSpawnPlanner() {
        this(new CoopResidentStateRestorer());
    }

    PlannedNpcProjectionSpawnPlanner(@Nonnull CoopResidentStateRestorer restorer) {
        this.restorer = Objects.requireNonNull(restorer, "restorer");
    }

    @Nonnull
    @Override
    public CoopResidentStateRestorer.PostAddWork installBeforeAdd(
            @Nonnull PlannedNpcProjectionSpawner.SpawnRequest request,
            @Nonnull PlannedNpcProjectionSpawner.PreAddTarget target) {
        target.replaceUuidComponent(request.plannedNpcUuid());
        target.setLegacyNpcUuid(request.plannedNpcUuid());
        return target.restoreFullState(
                restorer,
                request.fullSnapshot(),
                request.projectionMarker()
        );
    }
}
