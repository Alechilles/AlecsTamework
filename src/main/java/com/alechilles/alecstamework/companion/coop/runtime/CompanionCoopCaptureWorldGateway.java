package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Asynchronous world-thread receipt resolution and exact source retirement for one coop capture.
 *
 * <p>An implementation may confirm only after the exact physical receipt is durably saved and the
 * exact source is absent. Entity absence without that receipt is unknown.</p>
 */
@FunctionalInterface
public interface CompanionCoopCaptureWorldGateway {
    @Nonnull
    CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope operation
    );
}
