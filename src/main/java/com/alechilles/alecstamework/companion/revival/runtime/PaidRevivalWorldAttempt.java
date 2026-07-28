package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.CompositeProbe;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Mutation and durability results for one paid-revival live attempt.
 *
 * <p>The executor owns ordering. A platform gateway performs only the exact
 * observation, atomic mutation, save, or world-thread resumption requested by
 * this contract.</p>
 */
final class PaidRevivalWorldAttempt {
    private PaidRevivalWorldAttempt() {
    }

    enum ReceiptInstallStatus {
        EXACT,
        UNCHANGED,
        CONFLICT
    }

    record ReceiptInstall(
            @Nonnull ReceiptInstallStatus status,
            @Nullable Throwable cause
    ) {
        ReceiptInstall {
            if (status == null) {
                throw new IllegalArgumentException(
                        "Paid revival receipt-install status is required"
                );
            }
        }

        static ReceiptInstall exact() {
            return new ReceiptInstall(ReceiptInstallStatus.EXACT, null);
        }

        static ReceiptInstall unchanged(@Nullable Throwable cause) {
            return new ReceiptInstall(
                    ReceiptInstallStatus.UNCHANGED, cause
            );
        }

        static ReceiptInstall conflict(@Nullable Throwable cause) {
            return new ReceiptInstall(
                    ReceiptInstallStatus.CONFLICT, cause
            );
        }
    }

    enum ChargeAttemptStatus {
        CHARGED,
        UNCHANGED,
        RETRYABLE,
        PARTIAL,
        CONFLICT
    }

    record ChargeAttempt(
            @Nonnull ChargeAttemptStatus status,
            @Nullable Throwable cause
    ) {
        ChargeAttempt {
            if (status == null) {
                throw new IllegalArgumentException(
                        "Paid revival charge-attempt status is required"
                );
            }
        }

        static ChargeAttempt charged() {
            return new ChargeAttempt(ChargeAttemptStatus.CHARGED, null);
        }

        static ChargeAttempt unchanged(@Nullable Throwable cause) {
            return new ChargeAttempt(
                    ChargeAttemptStatus.UNCHANGED, cause
            );
        }

        static ChargeAttempt retryable(@Nullable Throwable cause) {
            return new ChargeAttempt(
                    ChargeAttemptStatus.RETRYABLE, cause
            );
        }

        static ChargeAttempt partial(@Nullable Throwable cause) {
            return new ChargeAttempt(
                    ChargeAttemptStatus.PARTIAL, cause
            );
        }

        static ChargeAttempt conflict(@Nullable Throwable cause) {
            return new ChargeAttempt(
                    ChargeAttemptStatus.CONFLICT, cause
            );
        }
    }

    enum ProjectionAttemptStatus {
        EXACT,
        TERMINAL_ABSENT,
        RETRYABLE,
        CONFLICT
    }

    record ProjectionAttempt(
            @Nonnull ProjectionAttemptStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        ProjectionAttempt {
            if (status == null
                    || (status == ProjectionAttemptStatus.EXACT)
                    != (chunkIndex != null)) {
                throw new IllegalArgumentException(
                        "Paid revival projection result is inconsistent"
                );
            }
        }

        static ProjectionAttempt exact(long chunkIndex) {
            return new ProjectionAttempt(
                    ProjectionAttemptStatus.EXACT,
                    chunkIndex,
                    null
            );
        }

        static ProjectionAttempt terminalAbsent(
                @Nullable Throwable cause
        ) {
            return new ProjectionAttempt(
                    ProjectionAttemptStatus.TERMINAL_ABSENT,
                    null,
                    cause
            );
        }

        static ProjectionAttempt retryable(@Nullable Throwable cause) {
            return new ProjectionAttempt(
                    ProjectionAttemptStatus.RETRYABLE,
                    null,
                    cause
            );
        }

        static ProjectionAttempt conflict(@Nullable Throwable cause) {
            return new ProjectionAttempt(
                    ProjectionAttemptStatus.CONFLICT,
                    null,
                    cause
            );
        }
    }

    enum PersistenceStatus {
        SAVED,
        RETRYABLE,
        CONFLICT
    }

    record ActorPersistence(
            @Nonnull PersistenceStatus status,
            @Nullable Throwable cause
    ) {
        ActorPersistence {
            if (status == null) {
                throw new IllegalArgumentException(
                        "Paid revival actor-persistence status is required"
                );
            }
        }

        static ActorPersistence saved() {
            return new ActorPersistence(PersistenceStatus.SAVED, null);
        }

        static ActorPersistence retryable(@Nullable Throwable cause) {
            return new ActorPersistence(
                    PersistenceStatus.RETRYABLE, cause
            );
        }

        static ActorPersistence conflict(@Nullable Throwable cause) {
            return new ActorPersistence(
                    PersistenceStatus.CONFLICT, cause
            );
        }
    }

    record TargetPersistence(
            @Nonnull PersistenceStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        TargetPersistence {
            if (status == null
                    || (status == PersistenceStatus.SAVED)
                    != (chunkIndex != null)) {
                throw new IllegalArgumentException(
                        "Paid revival target persistence is inconsistent"
                );
            }
        }

        static TargetPersistence saved(long chunkIndex) {
            return new TargetPersistence(
                    PersistenceStatus.SAVED, chunkIndex, null
            );
        }

        static TargetPersistence retryable(@Nullable Throwable cause) {
            return new TargetPersistence(
                    PersistenceStatus.RETRYABLE, null, cause
            );
        }

        static TargetPersistence conflict(@Nullable Throwable cause) {
            return new TargetPersistence(
                    PersistenceStatus.CONFLICT, null, cause
            );
        }
    }

    interface AttemptGateway {
        @Nonnull
        CompositeProbe probeComposite();

        @Nonnull
        CompositeProbe probeCompositeInTargetChunk(long chunkIndex);

        @Nonnull
        ReceiptInstall installExactReceipt();

        @Nonnull
        ChargeAttempt consumeExactRecipe();

        @Nonnull
        ProjectionAttempt applyOrResolveProjection();

        @Nonnull
        CompletionStage<ActorPersistence> persistActor();

        @Nonnull
        CompletionStage<TargetPersistence> persistTargetChunk(
                long chunkIndex
        );

        @Nonnull
        CompletionStage<PaidRevivalLiveResult> resumeOnWorldThread(
                @Nonnull Supplier<CompletionStage<PaidRevivalLiveResult>>
                        continuation
        );
    }
}
