package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureBoundary;
import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureReleaseBoundary;
import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureReleaseWorldGateway;
import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureWorldGateway;
import com.alechilles.alecstamework.companion.capture.runtime.TameworkCaptureSourceReceiptsComponent;
import com.alechilles.alecstamework.companion.command.timed.runtime.HytaleTimedSummonBoundary;
import com.alechilles.alecstamework.companion.command.timed.runtime.HytaleTimedSummonWorldGateway;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopCaptureBoundary;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopCaptureWorldGateway;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopReleaseBoundary;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopReleaseWorldGateway;
import com.alechilles.alecstamework.companion.coop.runtime.TameworkCoopCaptureReceiptsComponent;
import com.alechilles.alecstamework.companion.provisioning.runtime.HytaleProvisioningActivationBoundary;
import com.alechilles.alecstamework.companion.provisioning.runtime.HytaleProvisioningActivationWorldGateway;
import com.alechilles.alecstamework.companion.restoration.runtime.HytaleCompanionRestorationBoundary;
import com.alechilles.alecstamework.companion.restoration.runtime.HytaleCompanionRestorationWorldGateway;
import com.alechilles.alecstamework.companion.revival.PaidRevivalBoundaries;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalBoundary;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalCanonicalCleanupBoundary;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalReleaseBoundary;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalWorldGateway;
import com.alechilles.alecstamework.items.CoopEffectService;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Creates every Hytale live boundary around one codec registry and projection executor.
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
                    > retirementType,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent
                    > inventoryReceiptsType
    ) {
        if (captureSourceReceiptsType == null
                || coopCaptureReceiptsType == null
                || retirementType == null
                || inventoryReceiptsType == null) {
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
                ),
                new HytaleTimedSummonBoundary(
                        new HytaleTimedSummonWorldGateway(
                                codecs, projections, retirementType
                        )
                ),
                new HytaleProvisioningActivationBoundary(
                        new HytaleProvisioningActivationWorldGateway(
                                codecs, projections
                        )
                ),
                new PaidRevivalBoundaries(
                        new HytalePaidRevivalBoundary(
                                new HytalePaidRevivalWorldGateway(
                                        inventoryReceiptsType,
                                        codecs,
                                        projections
                                )
                        ),
                        new HytalePaidRevivalReleaseBoundary(
                                inventoryReceiptsType
                        ),
                        new HytalePaidRevivalCanonicalCleanupBoundary(
                                inventoryReceiptsType
                        )
                )
        );
    }
}
