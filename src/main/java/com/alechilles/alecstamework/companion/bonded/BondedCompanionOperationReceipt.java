package com.alechilles.alecstamework.companion.bonded;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Stable identity for one accepted bonded lifecycle operation. */
public record BondedCompanionOperationReceipt(
        @Nonnull String operationId,
        @Nonnull Action action,
        @Nonnull UUID actorOwnerUuid,
        long expectedRevision,
        long expectedPolicyRevision,
        long requestTimeMs,
        @Nonnull String payloadHash
) {
    public BondedCompanionOperationReceipt {
        operationId = text(operationId, "operationId");
        action = Objects.requireNonNull(action, "action");
        actorOwnerUuid = Objects.requireNonNull(
                actorOwnerUuid, "actorOwnerUuid"
        );
        payloadHash = text(payloadHash, "payloadHash");
        if (expectedRevision < -1L || expectedPolicyRevision < 0L) {
            throw new IllegalArgumentException("invalid operation revision");
        }
    }

    /** Creates a receipt without retaining potentially large payload text. */
    @Nonnull
    public static BondedCompanionOperationReceipt of(
            String operationId,
            Action action,
            UUID actorOwnerUuid,
            long expectedRevision,
            long expectedPolicyRevision,
            long requestTimeMs,
            String... payloadParts
    ) {
        MessageDigest digest = sha256();
        for (String part : payloadParts) {
            byte[] bytes = Objects.requireNonNull(part, "payload part")
                    .getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
        return new BondedCompanionOperationReceipt(
                operationId, action, actorOwnerUuid, expectedRevision,
                expectedPolicyRevision, requestTimeMs,
                HexFormat.of().formatHex(digest.digest())
        );
    }

    /** All state-changing operations recognized by the isolated lifecycle. */
    public enum Action {
        CAPTURE,
        PROVISION,
        SUMMON,
        STORE,
        CONFIRM_DEATH,
        REVIVE
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
