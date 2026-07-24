package com.alechilles.alecstamework.persistence.compensation.runtime;

import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationGateway;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Production refund boundary routed to the claim's immutable recipient world.
 *
 * <p>Only the durable claim and recipient UUID cross the asynchronous seam. Player, entity,
 * store, and inventory state are re-resolved inside the current world executor.</p>
 */
public final class HytaleRefundDeliveryBoundary
        implements RefundDeliveryBoundary {
    private final HytaleWorldOperationDispatcher dispatcher;
    private final HytaleWorldOperationGateway<RefundClaim> gateway;

    public HytaleRefundDeliveryBoundary() {
        this(
                new HytaleWorldOperationDispatcher(),
                new HytaleRefundDeliveryWorldGateway()
        );
    }

    HytaleRefundDeliveryBoundary(
            @Nonnull Function<String, World> worldLookup,
            @Nonnull HytaleWorldOperationGateway<RefundClaim> gateway
    ) {
        this(new HytaleWorldOperationDispatcher(worldLookup), gateway);
    }

    HytaleRefundDeliveryBoundary(
            HytaleWorldOperationDispatcher dispatcher,
            HytaleWorldOperationGateway<RefundClaim> gateway
    ) {
        if (dispatcher == null || gateway == null) {
            throw new IllegalArgumentException(
                    "Refund boundary dependencies are required"
            );
        }
        this.dispatcher = dispatcher;
        this.gateway = gateway;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull RefundClaim claim,
            @Nonnull OperationEnvelope operation
    ) {
        if (claim == null || operation == null
                || !claim.operationId().equals(
                        operation.operationId()
                )) {
            return LiveOperationResult.unknown(
                    "refund_operation_mismatch",
                    null
            ).completed();
        }
        if (claim.delivered()) {
            return LiveOperationResult.confirmed(
                    claim.deliveryEvidence()
            ).completed();
        }
        return dispatcher.applyOrResolve(
                "refund",
                claim.recipientWorldKey(),
                claim,
                operation,
                gateway
        );
    }
}
