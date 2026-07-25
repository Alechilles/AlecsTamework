package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ReceiptProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.SourceProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.StoreProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Stateful exact-evidence fake shared by timed world protocol tests. */
final class FakeTimedSummonWorldAttempts implements AttemptGateway {
    ProjectionProbe startProbe = ProjectionProbe.absent();
    StoreProbe storeProbe = StoreProbe.of(
            ReceiptProbe.absent(),
            SourceProbe.exact(TimedSummonWorldTestFixture.CHUNK)
    );
    MutationAttempt spawnResult =
            MutationAttempt.exact(TimedSummonWorldTestFixture.CHUNK);
    MutationAttempt installResult =
            MutationAttempt.exact(TimedSummonWorldTestFixture.CHUNK);
    MutationAttempt retireResult =
            MutationAttempt.exact(TimedSummonWorldTestFixture.CHUNK);
    final List<String> events = new ArrayList<>();
    final Deque<CompletableFuture<ChunkPersistence>> persistence =
            new ArrayDeque<>();
    TimedSummonWorldAuthority.Start lastStartAuthority;
    TimedSummonWorldAuthority.Store lastStoreAuthority;
    boolean throwSpawnAfterApply;
    boolean throwInstallAfterApply;
    boolean throwRetireAfterApply;
    boolean suppressSpawnApply;
    boolean suppressInstallApply;
    boolean suppressRetireApply;

    CompletableFuture<ChunkPersistence> enqueuePendingPersistence() {
        CompletableFuture<ChunkPersistence> pending =
                new CompletableFuture<>();
        persistence.addLast(pending);
        return pending;
    }

    @Override
    public ProjectionProbe probeStart(
            TimedSummonWorldAuthority.Start authority
    ) {
        events.add("probe-start");
        lastStartAuthority = authority;
        return startProbe;
    }

    @Override
    public MutationAttempt spawnExact(
            TimedSummonWorldAuthority.Start authority
    ) {
        events.add("spawn");
        lastStartAuthority = authority;
        if (!suppressSpawnApply
                && spawnResult.status()
                == TimedSummonWorldAttempt.MutationStatus.EXACT) {
            startProbe = ProjectionProbe.exact(
                    spawnResult.chunkIndex()
            );
        }
        if (throwSpawnAfterApply) {
            throw new IllegalStateException("spawn interruption");
        }
        return spawnResult;
    }

    @Override
    public StoreProbe probeStore(
            TimedSummonWorldAuthority.Store authority
    ) {
        events.add("probe-store");
        lastStoreAuthority = authority;
        return storeProbe;
    }

    @Override
    public MutationAttempt installRetirementReceipt(
            TimedSummonWorldAuthority.Store authority
    ) {
        events.add("install-receipt");
        lastStoreAuthority = authority;
        if (!suppressInstallApply
                && installResult.status()
                == TimedSummonWorldAttempt.MutationStatus.EXACT) {
            storeProbe = StoreProbe.of(
                    ReceiptProbe.exact(installResult.chunkIndex()),
                    storeProbe.source()
            );
        }
        if (throwInstallAfterApply) {
            throw new IllegalStateException("receipt interruption");
        }
        return installResult;
    }

    @Override
    public MutationAttempt retireExactSource(
            TimedSummonWorldAuthority.Store authority
    ) {
        events.add("retire");
        lastStoreAuthority = authority;
        if (!suppressRetireApply
                && retireResult.status()
                == TimedSummonWorldAttempt.MutationStatus.EXACT) {
            storeProbe = StoreProbe.of(
                    storeProbe.receipt(),
                    SourceProbe.absent()
            );
        }
        if (throwRetireAfterApply) {
            throw new IllegalStateException("retirement interruption");
        }
        return retireResult;
    }

    @Override
    public CompletionStage<ChunkPersistence> persistChunkAndReadBack(
            long chunkIndex
    ) {
        events.add("persist:" + chunkIndex);
        return persistence.isEmpty()
                ? CompletableFuture.completedFuture(
                        ChunkPersistence.saved(chunkIndex)
                )
                : persistence.removeFirst();
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeOnWorldThread(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        events.add("resume");
        return continuation.get();
    }
}
