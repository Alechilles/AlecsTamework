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
import java.util.Map;
import java.util.concurrent.CompletionStage;

/** Focused test seam for the replacement operations and reads used by direct-live coops. */
interface DirectLiveCoopPersistencePort {
    Map<CoopSlotKey, CoopOccupancy> projectedCoopSnapshot();

    CompletionStage<PersistenceReadResult<CoopSlot>> findCoopSlot(
            CoopSlotKey slot
    );

    CompletionStage<PersistenceReadResult<CoopResidency>>
    findCoopResidency(ProfileId profileId);

    CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(NpcAlias alias);

    CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(ProfileId profileId);

    PublicOperationSubmission registerCoopSlot(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CoopSlotRegistration registration
    );

    PublicOperationSubmission mutateProfile(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionProfileMutation mutation
    );

    PublicOperationSubmission captureToCoop(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCoopCaptureRequest capture
    );

    PublicOperationSubmission releaseFromCoop(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCoopReleaseRequest release
    );
}
