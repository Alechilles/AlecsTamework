package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;

/** Routes companion capture through the shared current-world dispatcher. */
public final class HytaleCompanionCaptureBoundary
        implements CompanionCaptureLiveBoundary {
    private final CompanionCaptureWorldGateway gateway;
    private final HytaleWorldOperationDispatcher dispatcher;

    public HytaleCompanionCaptureBoundary(
            @Nonnull CompanionCaptureWorldGateway gateway
    ) {
        this(gateway, new HytaleWorldOperationDispatcher());
    }

    HytaleCompanionCaptureBoundary(
            @Nonnull CompanionCaptureWorldGateway gateway,
            @Nonnull Function<String, World> worldLookup
    ) {
        this(gateway, new HytaleWorldOperationDispatcher(worldLookup));
    }

    HytaleCompanionCaptureBoundary(
            CompanionCaptureWorldGateway gateway,
            HytaleWorldOperationDispatcher dispatcher
    ) {
        if (gateway == null || dispatcher == null) {
            throw new IllegalArgumentException(
                    "Capture world boundary dependencies are required"
            );
        }
        this.gateway = gateway;
        this.dispatcher = dispatcher;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull CompanionCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return dispatcher.applyOrResolve(
                "capture",
                request == null ? null : request.targetWorldKey(),
                request,
                operation,
                gateway
        );
    }
}
