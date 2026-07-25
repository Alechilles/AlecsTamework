package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureBoundary;
import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureReleaseBoundary;
import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureReleaseWorldGateway;
import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureWorldGateway;
import com.alechilles.alecstamework.companion.capture.runtime.TameworkCaptureSourceReceiptsComponent;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopCaptureBoundary;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopCaptureWorldGateway;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopReleaseBoundary;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopReleaseWorldGateway;
import com.alechilles.alecstamework.companion.coop.runtime.TameworkCoopCaptureReceiptsComponent;
import com.alechilles.alecstamework.companion.restoration.runtime.HytaleCompanionRestorationBoundary;
import com.alechilles.alecstamework.companion.restoration.runtime.HytaleCompanionRestorationWorldGateway;
import com.alechilles.alecstamework.items.CoopEffectService;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Creates the five released Hytale live boundaries around shared codecs and projection spawning.
 */
public final class HytalePersistenceLiveBoundariesFactory {
    private HytalePersistenceLiveBoundariesFactory() {
    }

    @Nonnull
    public static PublicPersistenceLiveBoundaries create(
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkCaptureSourceReceiptsComponent
                    > captureSourceReceiptsType,
            @Nonnull ComponentType<
                    ChunkStore,
                    TameworkCoopCaptureReceiptsComponent
                    > coopCaptureReceiptsType,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirementType
    ) {
        if (captureSourceReceiptsType == null
                || coopCaptureReceiptsType == null
                || retirementType == null) {
            throw new IllegalArgumentException(
                    "Persistence receipt component types are required"
            );
        }
        var codecs = TameworkSnapshotCodecs.create();
        var projections = new HytaleCompanionProjectionSpawnExecutor();
        var coopEffects = new CoopEffectService();
        return new PublicPersistenceLiveBoundaries(
                new HytaleCompanionCaptureBoundary(
                        new HytaleCompanionCaptureWorldGateway(
                                captureSourceReceiptsType
                        )
                ),
                new HytaleCompanionCaptureReleaseBoundary(
                        new HytaleCompanionCaptureReleaseWorldGateway(
                                codecs, projections
                        )
                ),
                new HytaleCompanionRestorationBoundary(
                        new HytaleCompanionRestorationWorldGateway(
                                codecs, projections
                        )
                ),
                new HytaleCompanionCoopCaptureBoundary(
                        new HytaleCompanionCoopCaptureWorldGateway(
                                coopCaptureReceiptsType,
                                retirementType,
                                coopEffects
                        )
                ),
                new HytaleCompanionCoopReleaseBoundary(
                        new HytaleCompanionCoopReleaseWorldGateway(
                                codecs, projections, coopEffects
                        )
                )
        );
    }
}
