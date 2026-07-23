package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Applies or resolves one persistence operation using only current world-thread ECS state. */
@FunctionalInterface
public interface HytaleWorldOperationGateway<R> {
    @Nonnull
    LiveOperationResult applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull R request,
            @Nonnull OperationEnvelope operation
    ) throws Exception;
}
