package com.alechilles.alecstamework.companion.restoration.runtime;

import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * World-thread entity receipt resolution and insertion for one restoration.
 *
 * <p>Implementations receive only the current world and its current store. They must first resolve
 * {@code request.targetAlias()} and confirm the exact spawn receipt when present; absence permits
 * a new insertion but is never itself a confirmed result.</p>
 */
@FunctionalInterface
public interface CompanionRestorationWorldGateway {
    @Nonnull
    LiveOperationResult applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionRestorationRequest request,
            @Nonnull OperationEnvelope operation
    ) throws Exception;
}
