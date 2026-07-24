package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Replacement-facade adapter for free dormant restoration authoring. */
final class FacadeRestorationPersistencePort
        implements FreeCompanionRestorationAuthor.PersistencePort {
    private final PersistenceDomainFacades persistence;

    FacadeRestorationPersistencePort(PersistenceDomainFacades persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    @Override
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(ProfileId profileId) {
        return persistence.queries().findProfile(profileId);
    }

    @Override
    public PublicOperationSubmission restore(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionRestorationRequest request
    ) {
        return persistence.operations().restore(
                operationId, idempotencyKey, request
        );
    }
}
