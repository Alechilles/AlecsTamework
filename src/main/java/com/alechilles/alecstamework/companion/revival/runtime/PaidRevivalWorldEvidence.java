package com.alechilles.alecstamework.companion.revival.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact observations used to resolve one paid-revival live attempt.
 *
 * <p>Absence and unavailability are deliberately distinct. Only an explicit
 * {@link SpawnStatus#ABSENT} is positive spawn-absence evidence; a failed
 * observation never authorizes economic compensation.</p>
 */
final class PaidRevivalWorldEvidence {
    private PaidRevivalWorldEvidence() {
    }

    enum ReceiptStatus {
        EXACT,
        ABSENT,
        UNAVAILABLE,
        CONFLICT
    }

    record ReceiptProbe(
            @Nonnull ReceiptStatus status,
            @Nullable Throwable cause
    ) {
        ReceiptProbe {
            if (status == null) {
                throw new IllegalArgumentException(
                        "Paid revival receipt status is required"
                );
            }
        }

        static ReceiptProbe exact() {
            return new ReceiptProbe(ReceiptStatus.EXACT, null);
        }

        static ReceiptProbe absent() {
            return new ReceiptProbe(ReceiptStatus.ABSENT, null);
        }

        static ReceiptProbe unavailable(@Nullable Throwable cause) {
            return new ReceiptProbe(ReceiptStatus.UNAVAILABLE, cause);
        }

        static ReceiptProbe conflict(@Nullable Throwable cause) {
            return new ReceiptProbe(ReceiptStatus.CONFLICT, cause);
        }
    }

    /**
     * Exact economic state for the complete frozen recipe.
     *
     * <p>{@code EMPTY} is valid only for an actually empty recipe. A nonempty
     * recipe may be either wholly unchanged or wholly charged; partial state
     * is never safe to compensate automatically.</p>
     */
    enum ChargeStatus {
        UNCHANGED,
        CHARGED,
        EMPTY,
        UNAVAILABLE,
        PARTIAL,
        CONFLICT
    }

    record ChargeProbe(
            @Nonnull ChargeStatus status,
            @Nullable Throwable cause
    ) {
        ChargeProbe {
            if (status == null) {
                throw new IllegalArgumentException(
                        "Paid revival charge status is required"
                );
            }
        }

        static ChargeProbe unchanged() {
            return new ChargeProbe(ChargeStatus.UNCHANGED, null);
        }

        static ChargeProbe charged() {
            return new ChargeProbe(ChargeStatus.CHARGED, null);
        }

        static ChargeProbe empty() {
            return new ChargeProbe(ChargeStatus.EMPTY, null);
        }

        static ChargeProbe unavailable(@Nullable Throwable cause) {
            return new ChargeProbe(ChargeStatus.UNAVAILABLE, cause);
        }

        static ChargeProbe partial(@Nullable Throwable cause) {
            return new ChargeProbe(ChargeStatus.PARTIAL, cause);
        }

        static ChargeProbe conflict(@Nullable Throwable cause) {
            return new ChargeProbe(ChargeStatus.CONFLICT, cause);
        }
    }

    enum SpawnStatus {
        EXACT,
        ABSENT,
        UNAVAILABLE,
        CONFLICT
    }

    record SpawnProbe(
            @Nonnull SpawnStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        SpawnProbe {
            if (status == null
                    || (status == SpawnStatus.EXACT)
                    != (chunkIndex != null)) {
                throw new IllegalArgumentException(
                        "Paid revival spawn evidence is inconsistent"
                );
            }
        }

        static SpawnProbe exact(long chunkIndex) {
            return new SpawnProbe(SpawnStatus.EXACT, chunkIndex, null);
        }

        static SpawnProbe absent() {
            return new SpawnProbe(SpawnStatus.ABSENT, null, null);
        }

        static SpawnProbe unavailable(@Nullable Throwable cause) {
            return new SpawnProbe(
                    SpawnStatus.UNAVAILABLE, null, cause
            );
        }

        static SpawnProbe conflict(@Nullable Throwable cause) {
            return new SpawnProbe(SpawnStatus.CONFLICT, null, cause);
        }
    }

    record CompositeProbe(
            @Nonnull ReceiptProbe receipt,
            @Nonnull ChargeProbe charge,
            @Nonnull SpawnProbe spawn
    ) {
        CompositeProbe {
            if (receipt == null || charge == null || spawn == null) {
                throw new IllegalArgumentException(
                        "Complete paid revival evidence is required"
                );
            }
        }

        static CompositeProbe of(
                ReceiptProbe receipt,
                ChargeProbe charge,
                SpawnProbe spawn
        ) {
            return new CompositeProbe(receipt, charge, spawn);
        }
    }
}
