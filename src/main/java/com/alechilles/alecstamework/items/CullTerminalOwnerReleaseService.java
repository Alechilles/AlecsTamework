package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;

/**
 * Clears the exact durable owner of a live cull target before its death is
 * applied, so permanent culling cannot leave population capacity occupied.
 */
final class CullTerminalOwnerReleaseService {
    enum Outcome {
        RELEASED,
        NOT_TRACKED,
        UNAVAILABLE
    }

    @FunctionalInterface
    interface Port {
        CompletionStage<Outcome> release(UUID ownerUuid, UUID npcUuid);
    }

    private final PersistenceDomainFacades persistence;

    private CullTerminalOwnerReleaseService(PersistenceDomainFacades persistence) {
        this.persistence = persistence;
    }

    static Port from(@Nullable PersistenceDomainFacades persistence) {
        return persistence == null
                ? (ownerUuid, npcUuid) -> unavailable()
                : new CullTerminalOwnerReleaseService(persistence)::release;
    }

    private CompletionStage<Outcome> release(UUID ownerUuid, UUID npcUuid) {
        if (ownerUuid == null || npcUuid == null) {
            return unavailable();
        }
        try {
            return persistence.queries().findProfile(new NpcAlias(npcUuid))
                    .thenCompose(read -> afterAliasRead(ownerUuid, npcUuid, read))
                    .exceptionally(failure -> Outcome.UNAVAILABLE);
        } catch (RuntimeException | LinkageError failure) {
            return unavailable();
        }
    }

    private CompletionStage<Outcome> afterAliasRead(
            UUID ownerUuid,
            UUID npcUuid,
            @Nullable PersistenceReadResult<CompanionProfileReadModel> read
    ) {
        if (read instanceof PersistenceReadResult.Absent<?>) {
            try {
                return persistence.queries().findProfile(new ProfileId(npcUuid))
                        .thenCompose(profileRead -> completeRead(
                                ownerUuid, npcUuid, profileRead
                        ));
            } catch (RuntimeException | LinkageError failure) {
                return unavailable();
            }
        }
        return completeRead(ownerUuid, npcUuid, read);
    }

    private CompletionStage<Outcome> completeRead(
            UUID ownerUuid,
            UUID npcUuid,
            @Nullable PersistenceReadResult<CompanionProfileReadModel> read
    ) {
        // Both alias history and the legacy profile-id fallback reported absence.
        // There is no durable membership to release for this ordinary live NPC.
        if (read instanceof PersistenceReadResult.Absent<?>) {
            return CompletableFuture.completedFuture(Outcome.NOT_TRACKED);
        }
        if (!(read instanceof PersistenceReadResult.Found<?>)) {
            return unavailable();
        }
        @SuppressWarnings("unchecked")
        var found = (PersistenceReadResult.Found<CompanionProfileReadModel>) read;
        CompanionProfileReadModel profile = found.value();
        var lifecycle = profile.lifecycle();
        if (profile.currentAlias() == null
                || !npcUuid.equals(profile.currentAlias().alias().value())
                || lifecycle.ownerId() == null
                || !ownerUuid.equals(lifecycle.ownerId().value())
                || lifecycle.state() == LifecycleState.RELEASED
                || lifecycle.state() == LifecycleState.CAPTURED
                || lifecycle.state() == LifecycleState.COOP) {
            return unavailable();
        }
        String operationKey = "cull-terminal-owner-release:"
                + lifecycle.profileId() + ":" + lifecycle.revision();
        OperationId operationId = new OperationId(UUID.nameUUIDFromBytes(
                operationKey.getBytes(StandardCharsets.UTF_8)
        ));
        try {
            var submission = persistence.operations().transitionOwnerPopulation(
                    operationId,
                    new IdempotencyKey(operationKey),
                    new OwnerPopulationTransitionRequest(
                            lifecycle.profileId(),
                            lifecycle.revision(),
                            lifecycle.ownerId(),
                            lifecycle.ownerWorldKey(),
                            null,
                            null,
                            0,
                            0,
                            lifecycle.stateChangedAtMs() + 1L
                    )
            );
            if (!submission.accepted()) {
                return unavailable();
            }
            return submission.completion().thenApply(result -> result != null
                    && result.status() == OperationWorkflowResult.Status.PUBLISHED
                    ? Outcome.RELEASED : Outcome.UNAVAILABLE);
        } catch (RuntimeException | LinkageError failure) {
            return unavailable();
        }
    }

    private static CompletionStage<Outcome> unavailable() {
        return CompletableFuture.completedFuture(Outcome.UNAVAILABLE);
    }
}
