package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleAsyncWorldOperationGateway;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** DURABLE/COMPENSATED exact paid-revival receipt cleanup gateway. */
final class HytalePaidRevivalCanonicalCleanupGateway
        implements HytaleAsyncWorldOperationGateway<PaidRevivalRequest> {
    private final HytalePaidRevivalReceiptCleanupGateway delegated;

    HytalePaidRevivalCanonicalCleanupGateway(
            ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType
    ) {
        delegated = new HytalePaidRevivalReceiptCleanupGateway(
                receiptType,
                HytalePaidRevivalReceiptCleanupGateway.CleanupMode
                        .POST_CANONICAL
        );
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolveAsync(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull PaidRevivalRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return delegated.applyOrResolveAsync(
                world, store, request, operation
        );
    }
}
