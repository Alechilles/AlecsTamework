package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Replacement-facade adapter for positive dormant authoring. */
final class FacadeDormantPersistencePort
        implements PositiveEvidenceDormantAuthor.PersistencePort {
    private final PersistenceDomainFacades persistence;

    FacadeDormantPersistencePort(PersistenceDomainFacades persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    @Override
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(ProfileId profileId) {
        return persistence.queries().findProfile(profileId);
    }

    @Override
    public PublicOperationSubmission makeDormant(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionDormantTransitionRequest request
    ) {
        return persistence.operations().makeDormant(
                operationId, idempotencyKey, request
        );
    }
}
