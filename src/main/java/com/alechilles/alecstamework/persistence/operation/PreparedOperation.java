package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Complete immutable request to prepare one shared operation envelope.
 */
public record PreparedOperation(@Nonnull OperationId operationId,
                                @Nonnull IdempotencyKey idempotencyKey,
                                @Nonnull OperationKind kind,
                                int payloadVersion,
                                @Nonnull String payloadJson,
                                @Nonnull String featureScope,
                                @Nullable LifecycleRevision expectedLifecycleRevision,
                                @Nonnull List<OperationScope> participants,
                                long createdAtMs) {
    public PreparedOperation {
        if (operationId == null || idempotencyKey == null || kind == null
                || payloadVersion <= 0 || payloadJson == null) {
            throw new IllegalArgumentException("Complete prepared operation identity and payload are required");
        }
        if (featureScope == null || featureScope.isBlank()) {
            throw new IllegalArgumentException("Prepared operation feature scope is required");
        }
        featureScope = featureScope.trim();
        TreeSet<OperationScope> normalized = new TreeSet<>();
        if (participants != null) {
            normalized.addAll(participants);
        }
        normalized.add(OperationScope.operation(operationId));
        normalized.add(new OperationScope(OperationScopeType.FEATURE, featureScope));
        participants = List.copyOf(new ArrayList<>(normalized));
    }
}
