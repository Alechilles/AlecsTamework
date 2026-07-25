package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Focused ports and classifications used by the tame-and-link live state machine. */
final class CompanionCaptureTameWorldAttempt {
    private CompanionCaptureTameWorldAttempt() {
    }

    enum AccessStatus {
        PRESENT,
        MISSING,
        CONFLICT
    }

    record AccessProbe(
            @Nonnull AccessStatus status,
            @Nullable Throwable cause
    ) {
        static AccessProbe present() {
            return new AccessProbe(AccessStatus.PRESENT, null);
        }

        static AccessProbe missing() {
            return new AccessProbe(AccessStatus.MISSING, null);
        }

        static AccessProbe conflict(@Nullable Throwable cause) {
            return new AccessProbe(AccessStatus.CONFLICT, cause);
        }
    }

    enum TargetStatus {
        UNCHANGED,
        APPLYING,
        TARGET,
        ABSENT,
        CONFLICT
    }

    record TargetProbe(
            @Nonnull TargetStatus status,
            boolean rolePending,
            @Nullable Throwable cause
    ) {
        static TargetProbe unchanged() {
            return new TargetProbe(TargetStatus.UNCHANGED, false, null);
        }

        static TargetProbe applying(boolean rolePending) {
            return new TargetProbe(
                    TargetStatus.APPLYING, rolePending, null
            );
        }

        static TargetProbe target() {
            return new TargetProbe(TargetStatus.TARGET, false, null);
        }

        static TargetProbe absent() {
            return new TargetProbe(TargetStatus.ABSENT, false, null);
        }

        static TargetProbe conflict(@Nullable Throwable cause) {
            return new TargetProbe(TargetStatus.CONFLICT, false, cause);
        }
    }

    enum MarkerStatus {
        EXACT,
        RETRYABLE,
        CONFLICT
    }

    record MarkerAttempt(
            @Nonnull MarkerStatus status,
            @Nullable Throwable cause
    ) {
        static MarkerAttempt exact() {
            return new MarkerAttempt(MarkerStatus.EXACT, null);
        }

        static MarkerAttempt retryable(@Nullable Throwable cause) {
            return new MarkerAttempt(MarkerStatus.RETRYABLE, cause);
        }

        static MarkerAttempt conflict(@Nullable Throwable cause) {
            return new MarkerAttempt(MarkerStatus.CONFLICT, cause);
        }
    }

    enum MutationStatus {
        APPLIED,
        ROLE_PENDING,
        RETRYABLE,
        CONFLICT
    }

    record MutationAttempt(
            @Nonnull MutationStatus status,
            @Nullable Throwable cause
    ) {
        static MutationAttempt applied() {
            return new MutationAttempt(MutationStatus.APPLIED, null);
        }

        static MutationAttempt rolePending() {
            return new MutationAttempt(MutationStatus.ROLE_PENDING, null);
        }

        static MutationAttempt retryable(@Nullable Throwable cause) {
            return new MutationAttempt(MutationStatus.RETRYABLE, cause);
        }

        static MutationAttempt conflict(@Nullable Throwable cause) {
            return new MutationAttempt(MutationStatus.CONFLICT, cause);
        }
    }

    enum PersistenceStatus {
        SAVED,
        RETRYABLE,
        CONFLICT
    }

    record ReceiptPersistence(
            @Nonnull PersistenceStatus status,
            @Nullable Throwable cause
    ) {
        static ReceiptPersistence saved() {
            return new ReceiptPersistence(PersistenceStatus.SAVED, null);
        }

        static ReceiptPersistence retryable(@Nullable Throwable cause) {
            return new ReceiptPersistence(
                    PersistenceStatus.RETRYABLE, cause
            );
        }

        static ReceiptPersistence conflict(@Nullable Throwable cause) {
            return new ReceiptPersistence(
                    PersistenceStatus.CONFLICT, cause
            );
        }
    }

    interface AttemptGateway
            extends ResolvedCaptureSourceWorldExecutor.Gateway {
        @Nonnull
        AccessProbe probeCommandAccess();

        @Nonnull
        TargetProbe probeTarget();

        boolean targetRoleResolvable();

        @Nonnull
        MarkerAttempt installTargetMarker();

        @Nonnull
        MutationAttempt convergeTarget();

        @Nonnull
        CompletionStage<ReceiptPersistence> persistActor();

        @Nonnull
        CompletionStage<ReceiptPersistence> persistTarget();

        @Nonnull
        CompletionStage<LiveOperationResult> resumeOnWorldThread(
                @Nonnull Supplier<CompletionStage<LiveOperationResult>>
                        continuation
        );

        @Nonnull
        CompletionStage<LiveOperationResult> resumeAfterWorldTick(
                @Nonnull Supplier<CompletionStage<LiveOperationResult>>
                        continuation
        );
    }
}
