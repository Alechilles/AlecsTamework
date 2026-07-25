package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ActorPersistence;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ChargeAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ReceiptInstall;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.TargetPersistence;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ChargeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.CompositeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Stateful exact-evidence gateway used by paid-revival protocol tests. */
final class FakePaidRevivalWorldAttempts implements AttemptGateway {
    final List<String> calls = new ArrayList<>();
    ReceiptProbe receipt = ReceiptProbe.absent();
    ChargeProbe charge = ChargeProbe.unchanged();
    SpawnProbe spawn = SpawnProbe.absent();
    ReceiptInstall install = ReceiptInstall.exact();
    ChargeAttempt chargeAttempt = ChargeAttempt.charged();
    ProjectionAttempt projection = ProjectionAttempt.exact(
            PaidRevivalWorldTestFixture.TARGET_CHUNK
    );
    int actorSaveFailureCall;
    boolean targetSaveFails;
    boolean targetReadbackAbsent;
    boolean probeThrows;
    boolean installThrows;
    boolean chargeThrows;
    boolean projectionThrows;
    boolean resumeThrows;
    boolean resumeNull;
    boolean actorSaveNull;
    boolean targetSaveNull;
    SpawnProbe spawnAfterChargeAttempt;
    ChargeProbe chargeAfterProjection;
    ChargeProbe chargeAfterActorSave;
    int chargeAfterActorSaveCall;
    int actorSaveCalls;
    int targetSaveCalls;

    static FakePaidRevivalWorldAttempts emptyCost() {
        FakePaidRevivalWorldAttempts attempts =
                new FakePaidRevivalWorldAttempts();
        attempts.charge = ChargeProbe.empty();
        return attempts;
    }

    @Override
    public CompositeProbe probeComposite() {
        calls.add("probe");
        if (probeThrows) {
            throw new IllegalStateException("probe failed");
        }
        return CompositeProbe.of(receipt, charge, spawn);
    }

    @Override
    public CompositeProbe probeCompositeInTargetChunk(long chunkIndex) {
        calls.add("probe-target");
        if (probeThrows) {
            throw new IllegalStateException("probe failed");
        }
        SpawnProbe observed = targetReadbackAbsent
                ? SpawnProbe.absent()
                : spawn;
        return CompositeProbe.of(receipt, charge, observed);
    }

    @Override
    public ReceiptInstall installExactReceipt() {
        calls.add("install");
        if (installThrows) {
            throw new IllegalStateException("install failed");
        }
        if (install.status()
                == PaidRevivalWorldAttempt.ReceiptInstallStatus.EXACT) {
            receipt = ReceiptProbe.exact();
        }
        return install;
    }

    @Override
    public ChargeAttempt consumeExactRecipe() {
        calls.add("charge");
        if (chargeThrows) {
            throw new IllegalStateException("charge failed");
        }
        if (chargeAttempt.status()
                == PaidRevivalWorldAttempt.ChargeAttemptStatus.CHARGED) {
            charge = ChargeProbe.charged();
        }
        if (spawnAfterChargeAttempt != null) {
            spawn = spawnAfterChargeAttempt;
        }
        return chargeAttempt;
    }

    @Override
    public ProjectionAttempt applyOrResolveProjection() {
        calls.add("project");
        if (projectionThrows) {
            throw new IllegalStateException("projection failed");
        }
        if (projection.status()
                == PaidRevivalWorldAttempt.ProjectionAttemptStatus.EXACT) {
            spawn = SpawnProbe.exact(projection.chunkIndex());
        }
        if (chargeAfterProjection != null) {
            charge = chargeAfterProjection;
        }
        return projection;
    }

    @Override
    public CompletionStage<ActorPersistence> persistActor() {
        calls.add("save-actor");
        actorSaveCalls++;
        if (actorSaveNull) {
            return null;
        }
        if (actorSaveCalls == actorSaveFailureCall) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("actor save failed")
            );
        }
        if (actorSaveCalls == chargeAfterActorSaveCall) {
            charge = chargeAfterActorSave;
        }
        return CompletableFuture.completedFuture(
                ActorPersistence.saved()
        );
    }

    @Override
    public CompletionStage<TargetPersistence> persistTargetChunk(
            long chunkIndex
    ) {
        calls.add("save-target");
        targetSaveCalls++;
        if (targetSaveNull) {
            return null;
        }
        if (targetSaveFails) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("target save failed")
            );
        }
        return CompletableFuture.completedFuture(
                TargetPersistence.saved(chunkIndex)
        );
    }

    @Override
    public CompletionStage<PaidRevivalLiveResult> resumeOnWorldThread(
            Supplier<CompletionStage<PaidRevivalLiveResult>> continuation
    ) {
        calls.add("resume");
        if (resumeThrows) {
            throw new IllegalStateException("resume failed");
        }
        return resumeNull ? null : continuation.get();
    }
}
