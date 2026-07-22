package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable, restart-visible state for one paid command revival. */
public record PaidCommandRevivalRecord(
        @Nonnull UUID operationId,
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull UUID ownerUuid,
        @Nonnull String profileId,
        @Nonnull String commandFamilyId,
        @Nonnull String roleId,
        @Nullable String configId,
        @Nonnull String configRevision,
        long deathRevision,
        long profileRevision,
        @Nullable String populationAdmissionOperationId,
        @Nullable String placementFingerprint,
        @Nullable String reviveProjectionOperationId,
        @Nonnull State state,
        @Nonnull List<ItemCostComponentView> exactCost,
        @Nonnull List<Reservation> reservations,
        @Nullable String detail,
        long createdAtMs,
        long updatedAtMs,
        @Nullable Long completedAtMs
) {
    public PaidCommandRevivalRecord {
        operationId = Objects.requireNonNull(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        profileId = requireText(profileId, "profileId");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        roleId = requireText(roleId, "roleId");
        configId = normalize(configId);
        configRevision = requireText(configRevision, "configRevision");
        if (deathRevision < 0L) throw new IllegalArgumentException("deathRevision cannot be negative");
        if (profileRevision < 0L) throw new IllegalArgumentException("profileRevision cannot be negative");
        populationAdmissionOperationId = normalize(populationAdmissionOperationId);
        placementFingerprint = normalize(placementFingerprint);
        reviveProjectionOperationId = normalize(reviveProjectionOperationId);
        state = Objects.requireNonNull(state, "state");
        exactCost = List.copyOf(Objects.requireNonNull(exactCost, "exactCost"));
        reservations = List.copyOf(Objects.requireNonNull(reservations, "reservations"));
        detail = normalize(detail);
        if (createdAtMs < 0L || updatedAtMs < createdAtMs) {
            throw new IllegalArgumentException("invalid operation timestamps");
        }
        if (completedAtMs != null && completedAtMs < createdAtMs) {
            throw new IllegalArgumentException("completedAtMs predates creation");
        }
    }

    public enum State {
        PREPARED, RESERVED, COST_CONSUMED, APPLYING, SUCCEEDED, CANCELED,
        REFUND_REQUIRED, REFUNDED, QUARANTINED
    }

    public record Reservation(int costOrdinal,
                              int stackOrdinal,
                              @Nonnull String compartmentId,
                              int slotIndex,
                              int quantity,
                              @Nonnull String sourceStackFingerprint,
                              long reservationGeneration,
                              @Nonnull ReservationState state) {
        public Reservation {
            if (costOrdinal < 0 || stackOrdinal < 0 || slotIndex < 0 || quantity <= 0
                    || reservationGeneration < 0L) {
                throw new IllegalArgumentException("invalid reservation coordinates or quantity");
            }
            compartmentId = requireText(compartmentId, "compartmentId");
            sourceStackFingerprint = requireText(sourceStackFingerprint, "sourceStackFingerprint");
            state = Objects.requireNonNull(state, "state");
        }
    }

    public enum ReservationState { HELD, CONSUMED, RELEASED, REFUND_REQUIRED, REFUNDED }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
