package com.alechilles.alecstamework.companion.restoration.runtime;

import com.alechilles.alecstamework.companion.restoration.CompanionRestorationLiveBoundary;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Dispatches restoration to the named current world and resolves ECS state only on its thread.
 *
 * <p>No {@code World}, {@code Store}, {@code Ref}, or component crosses the asynchronous
 * persistence boundary. A world replacement between scheduling and execution is treated as a
 * retry, so the next attempt resolves the new current world instead of touching stale ECS state.</p>
 */
public final class HytaleCompanionRestorationBoundary
        implements CompanionRestorationLiveBoundary {
    private final CompanionRestorationWorldGateway gateway;
    private final HytaleWorldOperationDispatcher dispatcher;

    public HytaleCompanionRestorationBoundary(
            @Nonnull CompanionRestorationWorldGateway gateway
    ) {
        this(gateway, new HytaleWorldOperationDispatcher());
    }

    HytaleCompanionRestorationBoundary(
            @Nonnull CompanionRestorationWorldGateway gateway,
            @Nonnull Function<String, World> worldLookup
    ) {
        this(gateway, new HytaleWorldOperationDispatcher(worldLookup));
    }

    private HytaleCompanionRestorationBoundary(
            CompanionRestorationWorldGateway gateway,
            HytaleWorldOperationDispatcher dispatcher
    ) {
        if (gateway == null || dispatcher == null) {
            throw new IllegalArgumentException(
                    "Restoration world boundary dependencies are required"
            );
        }
        this.gateway = gateway;
        this.dispatcher = dispatcher;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull CompanionRestorationRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return dispatcher.applyOrResolve(
                "restoration",
                request == null ? null : request.targetWorldKey(),
                request,
                operation,
                gateway
        );
    }
}
