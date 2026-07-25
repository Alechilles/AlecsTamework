package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** World-thread gateway whose durability barrier may complete asynchronously. */
@FunctionalInterface
public interface HytaleAsyncWorldOperationGateway<R> {
    @Nonnull
    CompletionStage<LiveOperationResult> applyOrResolveAsync(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull R request,
            @Nonnull OperationEnvelope operation
    );
}
