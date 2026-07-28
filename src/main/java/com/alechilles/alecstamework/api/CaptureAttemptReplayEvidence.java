package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Self-contained durable evidence for replaying one resolved capture notification.
 *
 * <p>Values are copied from the committed operation request. Consumers never need to join
 * current persistence state, which may have advanced since this event was emitted.</p>
 */
public record CaptureAttemptReplayEvidence(
        @Nonnull String operationIdempotencyKey,
        @Nullable UUID ownerUuid,
        @Nullable String callerNamespace,
        @Nullable String callerIdempotencyKey,
        @Nonnull String targetWorldKey,
        @Nullable String targetDisplayName,
        long expectedLifecycleRevision,
        @Nonnull Lifecycle lifecycle,
        @Nonnull Formula formula,
        @Nonnull Source source,
        @Nullable Snapshot snapshot,
        long requestedAtMs
) {
    public CaptureAttemptReplayEvidence {
        operationIdempotencyKey = text(
                operationIdempotencyKey, "operationIdempotencyKey"
        );
        callerNamespace = optional(callerNamespace);
        callerIdempotencyKey = optional(callerIdempotencyKey);
        targetWorldKey = text(targetWorldKey, "targetWorldKey");
        targetDisplayName = optional(targetDisplayName);
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        formula = Objects.requireNonNull(formula, "formula");
        source = Objects.requireNonNull(source, "source");
        if ((callerNamespace == null) != (callerIdempotencyKey == null)) {
            throw new IllegalArgumentException(
                    "Caller namespace and idempotency key must appear together."
            );
        }
        if (expectedLifecycleRevision < 0L) {
            throw new IllegalArgumentException(
                    "Expected lifecycle revision cannot be negative."
            );
        }
    }

    /** Post-commit lifecycle facts selected by the terminal disposition. */
    public record Lifecycle(
            @Nonnull String state,
            @Nonnull String locationKind,
            @Nonnull String locationKey,
            @Nullable String locationWorldKey,
            long revision
    ) {
        public Lifecycle {
            state = text(state, "state");
            locationKind = text(locationKind, "locationKind");
            locationKey = text(locationKey, "locationKey");
            locationWorldKey = optional(locationWorldKey);
            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "Lifecycle revision cannot be negative."
                );
            }
        }
    }

    /** Complete frozen capture configuration and roll evidence. */
    public record Formula(
            @Nonnull CaptureChanceMode chanceMode,
            double baseChance,
            double chancePerPower,
            double minimumChance,
            double maximumChance,
            double resistance,
            double chanceMultiplier,
            double missingHealthBonus,
            @Nullable Integer guaranteedAtPower,
            @Nonnull String requirementsHash,
            long requirementGeneration,
            @Nullable Double entropy,
            @Nullable Long failureCooldownUntilMs,
            @Nonnull CaptureSourceConsumption sourceConsumption,
            @Nonnull CaptureSuccessDisposition successDisposition,
            @Nullable Double currentHealth,
            @Nullable Double maximumHealth
    ) {
        public Formula {
            chanceMode = Objects.requireNonNull(chanceMode, "chanceMode");
            requirementsHash = text(requirementsHash, "requirementsHash");
            sourceConsumption = Objects.requireNonNull(
                    sourceConsumption, "sourceConsumption"
            );
            successDisposition = Objects.requireNonNull(
                    successDisposition, "successDisposition"
            );
            if (requirementGeneration < 0L
                    || (currentHealth == null) != (maximumHealth == null)) {
                throw new IllegalArgumentException(
                        "Capture formula replay evidence is inconsistent."
                );
            }
        }
    }

    /** Exact inventory source fence and receipt. */
    public record Source(
            int slot,
            int quantity,
            @Nonnull String beforeFingerprint,
            int remainingQuantity,
            @Nullable String remainingFingerprint,
            @Nonnull String receiptKey
    ) {
        public Source {
            beforeFingerprint = text(
                    beforeFingerprint, "beforeFingerprint"
            );
            remainingFingerprint = optional(remainingFingerprint);
            receiptKey = text(receiptKey, "receiptKey");
            if (slot < 0 || quantity <= 0
                    || remainingQuantity != quantity - 1
                    || (remainingQuantity == 0)
                    != (remainingFingerprint == null)) {
                throw new IllegalArgumentException(
                        "Capture source replay evidence is inconsistent."
                );
            }
        }
    }

    /** Exact immutable capture snapshot, present only for captured-item success. */
    public record Snapshot(
            @Nonnull UUID snapshotId,
            @Nonnull String kind,
            int payloadVersion,
            @Nonnull String payloadJson,
            @Nonnull String payloadHash,
            long sourceLifecycleRevision,
            boolean current,
            long createdAtMs
    ) {
        public Snapshot {
            snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
            kind = text(kind, "kind");
            payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
            payloadHash = text(payloadHash, "payloadHash");
            if (payloadVersion <= 0 || sourceLifecycleRevision < 0L) {
                throw new IllegalArgumentException(
                        "Capture snapshot replay evidence is invalid."
                );
            }
        }
    }

    private static String text(String value, String field) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
