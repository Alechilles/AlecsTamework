package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Production receipt-first world gateway for exact live companion capture. */
public final class HytaleCompanionCaptureWorldGateway
        implements CompanionCaptureWorldGateway {
    private final CompanionCaptureWorldExecutor executor;
    private final HytaleCapturedArtifactAdapter artifacts;

    public HytaleCompanionCaptureWorldGateway() {
        this(
                new CompanionCaptureWorldExecutor(),
                new HytaleCapturedArtifactAdapter()
        );
    }

    HytaleCompanionCaptureWorldGateway(
            @Nonnull CompanionCaptureWorldExecutor executor,
            @Nonnull HytaleCapturedArtifactAdapter artifacts
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    @Nonnull
    public LiveOperationResult applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (world == null || store == null) {
            return LiveOperationResult.unknown(
                    "capture_world_context_missing",
                    null
            );
        }
        try {
            store.assertThread();
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.unknown(
                    "capture_world_thread_unavailable",
                    failure
            );
        }
        return executor.execute(
                request,
                operation,
                new HytaleCompanionCaptureAttemptGateway(
                        world,
                        store,
                        request,
                        artifacts
                )
        );
    }
}
