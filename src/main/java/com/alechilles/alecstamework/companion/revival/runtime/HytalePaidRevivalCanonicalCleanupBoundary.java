package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.DurableOperationCleanupBoundary;
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
 * Retires exact charged/pending receipts only after canonical authority exists.
 */
public final class HytalePaidRevivalCanonicalCleanupBoundary
        implements DurableOperationCleanupBoundary<PaidRevivalRequest> {
    private static final String OPERATION_CODE = "paid_revival_cleanup";

    private final HytaleAsyncWorldOperationGateway<PaidRevivalRequest>
            gateway;
    private final HytaleWorldOperationDispatcher dispatcher;

    public HytalePaidRevivalCanonicalCleanupBoundary(
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType
    ) {
        this(
                new HytalePaidRevivalCanonicalCleanupGateway(receiptType),
                new HytaleWorldOperationDispatcher()
        );
    }

    HytalePaidRevivalCanonicalCleanupBoundary(
            HytaleAsyncWorldOperationGateway<PaidRevivalRequest> gateway,
            HytaleWorldOperationDispatcher dispatcher
    ) {
        if (gateway == null || dispatcher == null) {
            throw new IllegalArgumentException(
                    "Paid revival canonical cleanup dependencies are required"
            );
        }
        this.gateway = gateway;
        this.dispatcher = dispatcher;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> cleanupAfterDurable(
            @Nonnull PaidRevivalRequest request,
            @Nonnull OperationEnvelope durableOperation
    ) {
        return dispatcher.applyOrResolveAsync(
                OPERATION_CODE,
                request == null ? null : request.targetWorldKey(),
                request,
                durableOperation,
                gateway
        );
    }
}
