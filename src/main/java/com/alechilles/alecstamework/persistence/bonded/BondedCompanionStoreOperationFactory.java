package com.alechilles.alecstamework.persistence.bonded;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Builds the stable terminal-operation identity for one explicit store request. */
final class BondedCompanionStoreOperationFactory {
    private BondedCompanionStoreOperationFactory() {
    }

    static BondedCompanionOperation create(
            String callerNamespace,
            String idempotencyKey,
            UUID ownerUuid,
            String rosterId,
            String profileId,
            long expectedRevision,
            String worldKey,
            long attemptedAtMs,
            long retainedUntilMs
    ) {
        String canonical = canonical(ownerUuid, rosterId, profileId,
                expectedRevision, worldKey);
        return new BondedCompanionOperation(
                callerNamespace, idempotencyKey, sha256(canonical), ownerUuid,
                rosterId, profileId, BondedCompanionOperation.Type.STORE,
                attemptedAtMs, retainedUntilMs);
    }

    private static String canonical(
            UUID ownerUuid,
            String rosterId,
            String profileId,
            long expectedRevision,
            String worldKey
    ) {
        StringBuilder result = new StringBuilder("store-v1|");
        append(result, Objects.requireNonNull(ownerUuid, "ownerUuid").toString());
        append(result, rosterId);
        append(result, profileId);
        append(result, Long.toString(expectedRevision));
        append(result, worldKey);
        return result.toString();
    }

    private static void append(StringBuilder target, String value) {
        String normalized = Objects.requireNonNull(value, "store scope").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("store scope is required");
        }
        target.append(normalized.length()).append(':').append(normalized);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
