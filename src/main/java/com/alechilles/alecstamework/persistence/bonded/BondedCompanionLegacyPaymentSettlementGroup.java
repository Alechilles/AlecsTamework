package com.alechilles.alecstamework.persistence.bonded;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Complete historical identities that share one flattened payment key. */
public record BondedCompanionLegacyPaymentSettlementGroup(
        @Nonnull String operationId,
        @Nonnull List<BondedCompanionOperationProbe> operations
) {
    public BondedCompanionLegacyPaymentSettlementGroup {
        operationId = Objects.requireNonNull(operationId, "operationId").trim();
        if (operationId.isEmpty()) {
            throw new IllegalArgumentException("operationId is required");
        }
        operations = List.copyOf(Objects.requireNonNull(
                operations, "operations"));
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("operations are required");
        }
    }

    /** A shared flattened key can never authorize inventory settlement. */
    public boolean ambiguous() {
        return operations.size() != 1;
    }
}
