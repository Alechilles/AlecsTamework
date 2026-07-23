package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Routes coop insertion through the shared current-world dispatcher. */
public final class HytaleCompanionCoopReleaseBoundary
        implements CompanionCoopReleaseLiveBoundary {
    private final CompanionCoopReleaseWorldGateway gateway;
    private final HytaleWorldOperationDispatcher dispatcher;

    public HytaleCompanionCoopReleaseBoundary(
            @Nonnull CompanionCoopReleaseWorldGateway gateway
    ) {
        this(gateway, new HytaleWorldOperationDispatcher());
    }

    HytaleCompanionCoopReleaseBoundary(
            CompanionCoopReleaseWorldGateway gateway,
            HytaleWorldOperationDispatcher dispatcher
    ) {
        if (gateway == null || dispatcher == null) {
            throw new IllegalArgumentException(
                    "Coop release world boundary dependencies are required"
            );
        }
        this.gateway = gateway;
        this.dispatcher = dispatcher;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull CompanionCoopReleaseRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return dispatcher.applyOrResolve(
                "coop_release",
                request == null ? null : request.targetWorldKey(),
                request,
                operation,
                gateway
        );
    }
}
