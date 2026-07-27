package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
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
            List<BondedCompanionReviveCost> costs,
            long attemptedAtMs,
            long retainedUntilMs
    ) {
        return new BondedCompanionOperation(
                callerNamespace, idempotencyKey,
                requestHash(costs), ownerUuid, rosterId, profileId,
                BondedCompanionOperation.Type.REVIVE,
                attemptedAtMs, retainedUntilMs);
    }

    static String requestHash(String itemId, int quantity) {
        return requestHash(List.of(new BondedCompanionReviveCost(
                itemId, quantity)));
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
        return operation(callerNamespace, idempotencyKey, ownerUuid, rosterId,
                profileId, List.of(new BondedCompanionReviveCost(itemId, quantity)),
                attemptedAtMs, retainedUntilMs);
    }

    static String requestHash(List<BondedCompanionReviveCost> costs) {
        List<BondedCompanionReviveCost> recipe = List.copyOf(
                Objects.requireNonNull(costs, "costs"));
        if (recipe.isEmpty()) throw new IllegalArgumentException(
                "Exact revive payment is required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical(recipe).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String canonical(List<BondedCompanionReviveCost> costs) {
        StringBuilder encoded = new StringBuilder("v1|").append(costs.size())
                .append('|');
        for (BondedCompanionReviveCost cost : costs) {
            encoded.append(cost.itemId().length()).append(':')
                    .append(cost.itemId()).append(':')
                    .append(cost.quantity()).append(';');
        }
        return encoded.toString();
    }
}
