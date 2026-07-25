package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureBoundary;
import com.alechilles.alecstamework.companion.capture.runtime.HytaleCompanionCaptureReleaseBoundary;
import com.alechilles.alecstamework.companion.capture.runtime.TameworkCaptureSourceReceiptsComponent;
import com.alechilles.alecstamework.companion.command.timed.runtime.HytaleTimedSummonBoundary;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopCaptureBoundary;
import com.alechilles.alecstamework.companion.coop.runtime.HytaleCompanionCoopReleaseBoundary;
import com.alechilles.alecstamework.companion.coop.runtime.TameworkCoopCaptureReceiptsComponent;
import com.alechilles.alecstamework.companion.provisioning.runtime.HytaleProvisioningActivationBoundary;
import com.alechilles.alecstamework.companion.restoration.runtime.HytaleCompanionRestorationBoundary;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalBoundary;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalCanonicalCleanupBoundary;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalReleaseBoundary;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies that production composition supplies every concrete Hytale boundary. */
class HytalePersistenceLiveBoundariesFactoryTest {
    @Test
    void createsConcreteBoundariesForEveryExternalPersistenceEffect() {
        PublicPersistenceLiveBoundaries boundaries =
                HytalePersistenceLiveBoundariesFactory.create(
                        new ComponentType<
                                EntityStore,
                                TameworkCaptureSourceReceiptsComponent>(),
                        new ComponentType<
                                ChunkStore,
                                TameworkCoopCaptureReceiptsComponent>(),
                        new ComponentType<
                                EntityStore,
                                TameworkPersistenceRetirementComponent>(),
                        new ComponentType<
                                EntityStore,
                                TameworkInventoryOperationReceiptsComponent>()
                );

        assertInstanceOf(
                HytaleCompanionCaptureBoundary.class,
                boundaries.captures()
        );
        assertInstanceOf(
                HytaleCompanionCaptureReleaseBoundary.class,
                boundaries.capturedReleases()
        );
        assertInstanceOf(
                HytaleCompanionRestorationBoundary.class,
                boundaries.restorations()
        );
        assertInstanceOf(
                HytaleCompanionCoopCaptureBoundary.class,
                boundaries.coopCaptures()
        );
        assertInstanceOf(
                HytaleCompanionCoopReleaseBoundary.class,
                boundaries.coopReleases()
        );
        assertInstanceOf(
                HytaleTimedSummonBoundary.class,
                boundaries.timedSummons()
        );
        assertInstanceOf(
                HytaleProvisioningActivationBoundary.class,
                boundaries.provisioningActivations()
        );
        assertInstanceOf(
                HytalePaidRevivalBoundary.class,
                boundaries.paidRevivals().revivals()
        );
        assertInstanceOf(
                HytalePaidRevivalReleaseBoundary.class,
                boundaries.paidRevivals().releases()
        );
        assertInstanceOf(
                HytalePaidRevivalCanonicalCleanupBoundary.class,
                boundaries.paidRevivals().cleanups()
        );
    }

    @Test
    void rejectsMissingInventoryReceiptComponentType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HytalePersistenceLiveBoundariesFactory.create(
                        new ComponentType<
                                EntityStore,
                                TameworkCaptureSourceReceiptsComponent>(),
                        new ComponentType<
                                ChunkStore,
                                TameworkCoopCaptureReceiptsComponent>(),
                        new ComponentType<
                                EntityStore,
                                TameworkPersistenceRetirementComponent>(),
                        null
                )
        );
    }
}
