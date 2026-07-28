package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalReleaseBoundary;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleAsyncWorldOperationGateway;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Dispatches no-charge paid-revival receipt cleanup to the exact actor world.
 *
 * <p>The asynchronous gateway saves the player and re-enters the same world
 * instance before it confirms that both operation receipt keys are absent.</p>
 */
public final class HytalePaidRevivalReleaseBoundary
        implements PaidRevivalReleaseBoundary {
    private static final String OPERATION_CODE = "paid_revival_release";

    private final HytaleAsyncWorldOperationGateway<PaidRevivalRequest>
            gateway;
    private final HytaleWorldOperationDispatcher dispatcher;

    public HytalePaidRevivalReleaseBoundary(
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType
    ) {
        this(
                new HytalePaidRevivalReleaseGateway(receiptType),
                new HytaleWorldOperationDispatcher()
        );
    }

    HytalePaidRevivalReleaseBoundary(
            HytaleAsyncWorldOperationGateway<PaidRevivalRequest> gateway,
            HytaleWorldOperationDispatcher dispatcher
    ) {
        if (gateway == null || dispatcher == null) {
            throw new IllegalArgumentException(
                    "Paid revival release dependencies are required"
            );
        }
        this.gateway = gateway;
        this.dispatcher = dispatcher;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull PaidRevivalRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return dispatcher.applyOrResolveAsync(
                OPERATION_CODE,
                request == null ? null : request.targetWorldKey(),
                request,
                operation,
                gateway
        );
    }
}
