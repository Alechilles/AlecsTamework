package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionAttemptStatus;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Stateful exact-evidence gateway used by activation protocol tests. */
final class FakeProvisioningActivationWorldAttempt
        implements ProvisioningActivationWorldAttempt {
    final List<String> calls = new ArrayList<>();
    ProjectionProbe projection = ProjectionProbe.absent();
    ProjectionProbe finalProjection;
    ProjectionAttempt projectionAttempt = ProjectionAttempt.exact(
            ProvisioningActivationWorldTestFixture.TARGET_CHUNK
    );
    ChunkPersistence persistence = ChunkPersistence.saved(
            ProvisioningActivationWorldTestFixture.TARGET_CHUNK
    );
    int saveFailureCall;
    int saveCalls;
    boolean probeThrows;
    boolean projectionThrows;
    boolean saveThrows;
    boolean saveNull;
    boolean resumeThrows;
    boolean resumeNull;

    @Override
    public ProjectionProbe probe() {
        calls.add("probe");
        if (probeThrows) {
            throw new IllegalStateException("probe failed");
        }
        return projection;
    }

    @Override
    public ProjectionProbe probeInTargetChunk(long expectedChunkIndex) {
        calls.add("probe-target");
        if (probeThrows) {
            throw new IllegalStateException("probe failed");
        }
        return finalProjection == null ? projection : finalProjection;
    }

    @Override
    public ProjectionAttempt applyOrResolveExactProjection() {
        calls.add("project");
        if (projectionThrows) {
            throw new IllegalStateException("projection failed");
        }
        if (projectionAttempt.status()
                == ProjectionAttemptStatus.EXACT) {
            projection = ProjectionProbe.exact(
                    projectionAttempt.chunkIndex()
            );
        }
        return projectionAttempt;
    }

    @Override
    public CompletionStage<ChunkPersistence> persistTargetChunk(
            long chunkIndex
    ) {
        calls.add("save-target");
        saveCalls++;
        if (saveThrows) {
            throw new IllegalStateException("save failed");
        }
        if (saveNull) {
            return null;
        }
        if (saveFailureCall == saveCalls) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("save failed")
            );
        }
        return CompletableFuture.completedFuture(persistence);
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeOnWorldThread(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        calls.add("resume");
        if (resumeThrows) {
            throw new IllegalStateException("resume failed");
        }
        return resumeNull ? null : continuation.get();
    }
}
