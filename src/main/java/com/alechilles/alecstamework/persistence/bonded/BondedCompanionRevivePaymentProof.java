package com.alechilles.alecstamework.persistence.bonded;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Reconstructs the canonical revive request from exact durable escrow data. */
final class BondedCompanionRevivePaymentProof {
    private BondedCompanionRevivePaymentProof() {
    }

    static BondedCompanionOperation operation(
            String callerNamespace,
            String idempotencyKey,
            UUID ownerUuid,
            String rosterId,
            String profileId,
            String itemId,
            int quantity,
            long attemptedAtMs,
            long retainedUntilMs
    ) {
        return new BondedCompanionOperation(
                callerNamespace, idempotencyKey,
                requestHash(itemId, quantity), ownerUuid, rosterId, profileId,
                BondedCompanionOperation.Type.REVIVE,
                attemptedAtMs, retainedUntilMs);
    }

    static String requestHash(String itemId, int quantity) {
        String normalized = Objects.requireNonNull(itemId, "itemId").trim();
        if (normalized.isEmpty() || quantity <= 0) {
            throw new IllegalArgumentException(
                    "Exact revive payment is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (normalized + ":" + quantity)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
