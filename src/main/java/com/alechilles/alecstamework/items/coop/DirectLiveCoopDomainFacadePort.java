package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Production adapter from the shared domain facades to the focused coop author seam. */
final class DirectLiveCoopDomainFacadePort
        implements DirectLiveCoopPersistencePort {
    private final PersistenceDomainFacades facades;

    DirectLiveCoopDomainFacadePort(PersistenceDomainFacades facades) {
        this.facades = Objects.requireNonNull(
                facades, "Persistence facades"
        );
    }

    @Override
    public Map<CoopSlotKey, CoopOccupancy> projectedCoopSnapshot() {
        return facades.queries().projectedCoopSnapshot();
    }

    @Override
    public CompletionStage<PersistenceReadResult<CoopSlot>> findCoopSlot(
            CoopSlotKey slot
    ) {
        return facades.queries().findCoopSlot(slot);
    }

    @Override
    public CompletionStage<PersistenceReadResult<CoopResidency>>
    findCoopResidency(ProfileId profileId) {
        return facades.queries().findCoopResidency(profileId);
    }

    @Override
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(NpcAlias alias) {
        return facades.queries().findProfile(alias);
    }

    @Override
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(ProfileId profileId) {
        return facades.queries().findProfile(profileId);
    }

    @Override
    public PublicOperationSubmission registerCoopSlot(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CoopSlotRegistration registration
    ) {
        return facades.operations().registerCoopSlot(
                operationId, idempotencyKey, registration
        );
    }

    @Override
    public PublicOperationSubmission mutateProfile(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionProfileMutation mutation
    ) {
        return facades.operations().mutateProfile(
                operationId, idempotencyKey, mutation
        );
    }

    @Override
    public PublicOperationSubmission captureToCoop(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCoopCaptureRequest capture
    ) {
        return facades.operations().captureToCoop(
                operationId, idempotencyKey, capture
        );
    }

    @Override
    public PublicOperationSubmission releaseFromCoop(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCoopReleaseRequest release
    ) {
        return facades.operations().releaseFromCoop(
                operationId, idempotencyKey, release
        );
    }
}
