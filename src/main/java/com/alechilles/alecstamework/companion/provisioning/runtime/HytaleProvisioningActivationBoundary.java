package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationLiveBoundary;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Routes initial provisioning through the shared current-world dispatcher.
 */
public final class HytaleProvisioningActivationBoundary
        implements ProvisioningActivationLiveBoundary {
    private final ProvisioningActivationWorldGateway gateway;
    private final HytaleWorldOperationDispatcher dispatcher;

    public HytaleProvisioningActivationBoundary(
            @Nonnull ProvisioningActivationWorldGateway gateway
    ) {
        this(gateway, new HytaleWorldOperationDispatcher());
    }

    HytaleProvisioningActivationBoundary(
            ProvisioningActivationWorldGateway gateway,
            HytaleWorldOperationDispatcher dispatcher
    ) {
        if (gateway == null || dispatcher == null) {
            throw new IllegalArgumentException(
                    "Provisioning activation world dependencies are required"
            );
        }
        this.gateway = gateway;
        this.dispatcher = dispatcher;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull ProvisioningActivationRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return dispatcher.applyOrResolveAsync(
                "provisioning_activation",
                request == null ? null : request.targetWorldKey(),
                request,
                operation,
                gateway
        );
    }
}
