package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Routes coop retirement through the shared current-world dispatcher. */
public final class HytaleCompanionCoopCaptureBoundary
        implements CompanionCoopCaptureLiveBoundary {
    private final CompanionCoopCaptureWorldGateway gateway;
    private final HytaleWorldOperationDispatcher dispatcher;

    public HytaleCompanionCoopCaptureBoundary(
            @Nonnull CompanionCoopCaptureWorldGateway gateway
    ) {
        this(gateway, new HytaleWorldOperationDispatcher());
    }

    HytaleCompanionCoopCaptureBoundary(
            CompanionCoopCaptureWorldGateway gateway,
            HytaleWorldOperationDispatcher dispatcher
    ) {
        if (gateway == null || dispatcher == null) {
            throw new IllegalArgumentException(
                    "Coop capture world boundary dependencies are required"
            );
        }
        this.gateway = gateway;
        this.dispatcher = dispatcher;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return dispatcher.applyOrResolve(
                "coop_capture",
                request == null ? null : request.source().sourceWorldKey(),
                request,
                operation,
                gateway
        );
    }
}
