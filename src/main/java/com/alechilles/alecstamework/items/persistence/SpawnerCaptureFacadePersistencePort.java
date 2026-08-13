package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Adapts the replacement persistence facades to the narrow operations needed
 * while authoring a spawner capture.
 */
final class SpawnerCaptureFacadePersistencePort
        implements SpawnerCaptureAuthor.PersistencePort {
    private final PersistenceDomainFacades persistence;

    SpawnerCaptureFacadePersistencePort(PersistenceDomainFacades persistence) {
        this.persistence = Objects.requireNonNull(
                persistence, "persistence"
        );
    }

    @Override
    public Optional<CompanionProfileProjectionState> projectedProfile(
            NpcAlias alias
    ) {
        return persistence.queries().projectedProfile(alias);
    }

    @Override
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(ProfileId profileId) {
        return persistence.queries().findProfile(profileId);
    }

    @Override
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(NpcAlias alias) {
        return persistence.queries().findProfile(alias);
    }

    @Override
    public PublicOperationSubmission adopt(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionProfileMutation.AdoptLive adoption
    ) {
        return persistence.operations().mutateProfile(
                operationId, idempotencyKey, adoption
        );
    }

    @Override
    public PublicOperationSubmission capture(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCaptureRequest capture
    ) {
        return persistence.operations().capture(
                operationId, idempotencyKey, capture
        );
    }

    @Override
    public PublicOperationSubmission reconcile(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        return persistence.operations().mutateProfile(
                operationId, idempotencyKey, reconciliation
        );
    }

    @Override
    public boolean failureCooldownActive(
            UUID actorUuid,
            String itemConfigId,
            UUID currentAttemptId,
            long nowMs
    ) {
        return persistence.queries().activeCaptureFailureCooldown(
                actorUuid, itemConfigId, currentAttemptId, nowMs
        ).isPresent();
    }
}
