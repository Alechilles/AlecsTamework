package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact lifecycle evidence supplied to the internal managed-admission gateway. */
public record LifecycleAdmissionRequest(
        @Nonnull OperationId operationId,
        @Nonnull UUID reservationId,
        @Nonnull String targetRoleId,
        @Nullable PopulationAdmissionRequestV2 managedRequest,
        @Nullable CompanionLifecycle source,
        @Nullable LifecycleState sourceState,
        @Nonnull LifecycleState targetState,
        @Nullable OwnerId sourceOwner,
        @Nullable String sourceWorld
) {
    public LifecycleAdmissionRequest {
        operationId = Objects.requireNonNull(operationId, "operationId");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        targetRoleId = requireText(targetRoleId, "targetRoleId");
        targetState = Objects.requireNonNull(targetState, "targetState");
        if (managedRequest != null
                && !targetRoleId.equals(
                managedRequest.targetRoleId()
        )) {
            throw new IllegalArgumentException(
                    "Managed lifecycle role evidence does not match the request"
            );
        }
        if (sourceOwner == null && sourceWorld != null) {
            throw new IllegalArgumentException(
                    "An absent source owner cannot carry a source world"
            );
        }
    }

    /** Builds an unmanaged request that does not enter managed authoring. */
    @Nonnull
    public static LifecycleAdmissionRequest unmanaged(
            @Nonnull OperationId operationId,
            @Nonnull UUID reservationId,
            @Nonnull String targetRoleId,
            @Nullable CompanionLifecycle source,
            @Nullable LifecycleState sourceState,
            @Nonnull LifecycleState targetState,
            @Nullable OwnerId sourceOwner,
            @Nullable String sourceWorld
    ) {
        return new LifecycleAdmissionRequest(
                operationId,
                reservationId,
                targetRoleId,
                null,
                source,
                sourceState,
                targetState,
                sourceOwner,
                sourceWorld
        );
    }

    /** Builds a managed request for classification by the existing author. */
    @Nonnull
    public static LifecycleAdmissionRequest managed(
            @Nonnull OperationId operationId,
            @Nonnull UUID reservationId,
            @Nonnull String targetRoleId,
            @Nonnull PopulationAdmissionRequestV2 request,
            @Nullable CompanionLifecycle source,
            @Nullable LifecycleState sourceState,
            @Nonnull LifecycleState targetState,
            @Nullable OwnerId sourceOwner,
            @Nullable String sourceWorld
    ) {
        return new LifecycleAdmissionRequest(
                operationId,
                reservationId,
                targetRoleId,
                request,
                source,
                sourceState,
                targetState,
                sourceOwner,
                sourceWorld
        );
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
