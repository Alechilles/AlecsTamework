package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Connection-bound authority for coop structure, reservation, and residency detail.
 *
 * <p>Lifecycle remains the sole companion location authority. This port only owns slot uniqueness
 * and the typed detail required while lifecycle is {@code COOP}.</p>
 */
public interface CompanionCoopPort {
    @Nonnull
    Optional<CoopSlot> findSlot(@Nonnull CoopSlotKey slotKey);

    @Nonnull
    Optional<CoopResidency> findResidencyBySlot(@Nonnull CoopSlotKey slotKey);

    @Nonnull
    Optional<CoopResidency> findResidencyByProfile(@Nonnull ProfileId profileId);

    @Nonnull
    List<CoopOccupancy> findAllOccupancies();

    @Nonnull
    PersistenceMutationResult<CoopSlot> registerSlot(@Nonnull CoopSlot slot);

    @Nonnull
    PersistenceMutationResult<CoopSlot> reserveEmpty(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId,
            @Nonnull OperationId operationId
    );

    @Nonnull
    PersistenceMutationResult<CoopSlot> reserveOccupied(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId,
            @Nonnull OperationId operationId
    );

    @Nonnull
    PersistenceMutationResult<CoopOccupancy> commitCapture(
            @Nonnull CoopResidency residency,
            @Nonnull OperationId operationId
    );

    @Nonnull
    PersistenceMutationResult<CoopSlot> commitRelease(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId,
            @Nonnull OperationId operationId,
            long releasedAtMs
    );

    @Nonnull
    CoopConflictDiagnostic diagnoseCapture(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId
    );

    @Nonnull
    CoopConflictDiagnostic diagnoseRelease(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId
    );
}
