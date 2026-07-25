package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ChargeAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ReceiptInstall;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ChargeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ChargeStatus;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.CompositeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptStatus;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnStatus;
import javax.annotation.Nullable;

/** Fail-closed calls and exact evidence classification for paid revival. */
final class PaidRevivalWorldSafety {

    CompositeProbe probe(
            AttemptGateway attempts,
            @Nullable Long chunkIndex
    ) {
        try {
            CompositeProbe probe = chunkIndex == null
                    ? attempts.probeComposite()
                    : attempts.probeCompositeInTargetChunk(chunkIndex);
            return probe == null ? conflictingProbe(null) : probe;
        } catch (RuntimeException | LinkageError failure) {
            return conflictingProbe(failure);
        }
    }

    ReceiptInstall install(AttemptGateway attempts) {
        try {
            ReceiptInstall result = attempts.installExactReceipt();
            return result == null
                    ? ReceiptInstall.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptInstall.conflict(failure);
        }
    }

    ChargeAttempt charge(AttemptGateway attempts) {
        try {
            ChargeAttempt result = attempts.consumeExactRecipe();
            return result == null
                    ? ChargeAttempt.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ChargeAttempt.partial(failure);
        }
    }

    ProjectionAttempt project(AttemptGateway attempts) {
        try {
            ProjectionAttempt result = attempts.applyOrResolveProjection();
            return result == null
                    ? ProjectionAttempt.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionAttempt.conflict(failure);
        }
    }

    @Nullable
    PaidRevivalLiveResult probeFailure(
            PaidRevivalRequest request,
            CompositeProbe probe
    ) {
        if (recipeEvidenceConflict(request, probe.charge())
                || probe.receipt().status() == ReceiptStatus.CONFLICT
                || probe.charge().status() == ChargeStatus.PARTIAL
                || probe.charge().status() == ChargeStatus.CONFLICT
                || probe.spawn().status() == SpawnStatus.CONFLICT) {
            return unknown(
                    "composite_evidence_conflict", evidenceCause(probe)
            );
        }
        if (probe.receipt().status() == ReceiptStatus.UNAVAILABLE
                || probe.charge().status() == ChargeStatus.UNAVAILABLE
                || probe.spawn().status() == SpawnStatus.UNAVAILABLE) {
            return retryable(
                    "composite_evidence_unavailable", evidenceCause(probe)
            );
        }
        return null;
    }

    boolean uncharged(
            PaidRevivalRequest request,
            ChargeProbe charge
    ) {
        return request.exactCost().isEmpty()
                ? charge.status() == ChargeStatus.EMPTY
                : charge.status() == ChargeStatus.UNCHANGED;
    }

    boolean chargeComplete(
            PaidRevivalRequest request,
            ChargeProbe charge
    ) {
        return request.exactCost().isEmpty()
                ? charge.status() == ChargeStatus.EMPTY
                : charge.status() == ChargeStatus.CHARGED;
    }

    @Nullable
    Throwable evidenceCause(CompositeProbe probe) {
        return first(
                probe.receipt().cause(),
                first(probe.charge().cause(), probe.spawn().cause())
        );
    }

    @Nullable
    Throwable first(
            @Nullable Throwable first,
            @Nullable Throwable second
    ) {
        return first == null ? second : first;
    }

    PaidRevivalLiveResult retryable(
            String suffix,
            @Nullable Throwable cause
    ) {
        return PaidRevivalLiveResult.retryable(code(suffix), cause);
    }

    PaidRevivalLiveResult unknown(
            String suffix,
            @Nullable Throwable cause
    ) {
        return PaidRevivalLiveResult.unknown(code(suffix), cause);
    }

    String code(String suffix) {
        return "paid_revival_" + suffix;
    }

    private boolean recipeEvidenceConflict(
            PaidRevivalRequest request,
            ChargeProbe charge
    ) {
        return request.exactCost().isEmpty()
                ? charge.status() == ChargeStatus.CHARGED
                || charge.status() == ChargeStatus.UNCHANGED
                : charge.status() == ChargeStatus.EMPTY;
    }

    private CompositeProbe conflictingProbe(@Nullable Throwable cause) {
        return CompositeProbe.of(
                ReceiptProbe.conflict(cause),
                ChargeProbe.conflict(cause),
                SpawnProbe.conflict(cause)
        );
    }
}
