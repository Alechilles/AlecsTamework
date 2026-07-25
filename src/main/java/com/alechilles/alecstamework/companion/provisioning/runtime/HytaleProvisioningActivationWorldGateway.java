package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Opens an exact activation attempt on the current Hytale world thread. */
public final class HytaleProvisioningActivationWorldGateway
        implements ProvisioningActivationWorldGateway {
    private final ProvisioningActivationSnapshotResolver snapshots;
    private final HytaleCompanionProjectionSpawnExecutor projections;
    private final ProvisioningActivationWorldExecutor executor;

    public HytaleProvisioningActivationWorldGateway(
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this.snapshots = new ProvisioningActivationSnapshotResolver(
                Objects.requireNonNull(
                        snapshotCodecs, "snapshotCodecs"
                )
        );
        this.projections = Objects.requireNonNull(
                projections, "projections"
        );
        this.executor = new ProvisioningActivationWorldExecutor();
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolveAsync(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull ProvisioningActivationRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        try {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(operation, "operation");
            store.assertThread();
            if (!request.targetWorldKey().equals(world.getName())
                    || world.getEntityStore().getStore() != store) {
                return LiveOperationResult.retryable(
                        "provisioning_activation_world_instance_mismatch",
                        null
                ).completed();
            }
            HytaleProvisioningActivationProjectionGateway projection =
                    new HytaleProvisioningActivationProjectionGateway(
                            world,
                            store,
                            request,
                            operation,
                            snapshots,
                            projections
                    );
            return executor.execute(
                    request,
                    operation,
                    new HytaleProvisioningActivationWorldAttempt(
                            world, store, request, projection
                    )
            );
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.retryable(
                    "provisioning_activation_world_thread_unavailable",
                    failure
            ).completed();
        }
    }
}
