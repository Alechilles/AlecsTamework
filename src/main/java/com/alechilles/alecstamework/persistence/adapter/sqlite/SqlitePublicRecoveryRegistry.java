package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionRequest;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistrationDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationRequest;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentDefinition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentRequest;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryClaim;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Exhaustive, registry-validated routes from durable payloads to typed adapters. */
final class SqlitePublicRecoveryRegistry {
    private final Map<OperationKind, RecoveryHandler> handlers;

    SqlitePublicRecoveryRegistry(
            @Nonnull PersistenceFeatureRegistry features,
            @Nonnull SqlitePublicOperationSet operations,
            @Nonnull PublicPersistenceLiveBoundaries boundaries
    ) {
        if (features == null || operations == null || boundaries == null) {
            throw new IllegalArgumentException(
                    "Complete recovery registry dependencies are required"
            );
        }
        handlers = Map.ofEntries(
                Map.entry(
                        CompanionProfileMutationDefinition.INSTANCE.kind(),
                        claim -> operations.profiles().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(claim, CompanionProfileMutation.class)
                        ).completion()
                ),
                Map.entry(
                        CompanionAliasRotationDefinition.INSTANCE.kind(),
                        claim -> operations.aliases().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(claim, CompanionAliasRotation.class),
                                boundaries.aliases()
                        ).completion()
                ),
                Map.entry(
                        OwnerPopulationTransitionDefinition.INSTANCE.kind(),
                        claim -> operations.ownerPopulation().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(
                                        claim,
                                        OwnerPopulationTransitionRequest.class
                                )
                        ).completion()
                ),
                Map.entry(
                        OwnerPopulationReconciliationDefinition.INSTANCE.kind(),
                        claim -> operations
                                .ownerPopulationReconciliation()
                                .submit(
                                        claim.operation().operationId(),
                                        claim.operation().idempotencyKey(),
                                        payload(
                                                claim,
                                                OwnerPopulationReconciliationRequest
                                                        .class
                                        )
                                ).completion()
                ),
                Map.entry(
                        PopulationGroupAssignmentDefinition.INSTANCE.kind(),
                        claim -> operations.populationGroups().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(
                                        claim,
                                        PopulationGroupAssignmentRequest.class
                                )
                        ).completion()
                ),
                Map.entry(
                        CommandRosterMembershipDefinition.INSTANCE.kind(),
                        claim -> operations.commandRosters().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(
                                        claim,
                                        CommandRosterMembershipRequest.class
                                )
                        ).completion()
                ),
                Map.entry(
                        CommandRosterTransitionDefinition.INSTANCE.kind(),
                        claim -> operations.commandTransitions().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(
                                        claim,
                                        CommandRosterTransitionRequest.class
                                )
                        ).completion()
                ),
                Map.entry(
                        CompanionCaptureDefinition.INSTANCE.kind(),
                        claim -> operations.captures().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(claim, CompanionCaptureRequest.class),
                                boundaries.captures()
                        ).completion()
                ),
                Map.entry(
                        CompanionDormantTransitionDefinition.INSTANCE.kind(),
                        claim -> operations.dormant().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(
                                        claim,
                                        CompanionDormantTransitionRequest.class
                                )
                        ).completion()
                ),
                Map.entry(
                        CompanionRestorationDefinition.INSTANCE.kind(),
                        claim -> operations.restorations().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(claim, CompanionRestorationRequest.class),
                                boundaries.restorations()
                        ).completion()
                ),
                Map.entry(
                        CoopSlotRegistrationDefinition.INSTANCE.kind(),
                        claim -> operations.coopSlots().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(claim, CoopSlotRegistration.class)
                        ).completion()
                ),
                Map.entry(
                        CompanionCoopCaptureDefinition.INSTANCE.kind(),
                        claim -> operations.coopCaptures().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(claim, CompanionCoopCaptureRequest.class),
                                boundaries.coopCaptures()
                        ).completion()
                ),
                Map.entry(
                        CompanionCoopReleaseDefinition.INSTANCE.kind(),
                        claim -> operations.coopReleases().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(claim, CompanionCoopReleaseRequest.class),
                                boundaries.coopReleases()
                        ).completion()
                ),
                Map.entry(
                        ProfileExtensionMutationDefinition.INSTANCE.kind(),
                        claim -> operations.extensions().submit(
                                claim.operation().operationId(),
                                claim.operation().idempotencyKey(),
                                payload(claim, ProfileExtensionMutation.class)
                        ).completion()
                )
        );
        Set<OperationKind> declared = features.descriptors().stream()
                .flatMap(feature ->
                        feature.operationDefinitions().stream())
                .map(definition -> definition.kind())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!handlers.keySet().equals(declared)) {
            throw new IllegalArgumentException(
                    "Recovery routes do not match public operation registry"
            );
        }
    }

    @Nonnull
    CompletionStage<OperationWorkflowResult> dispatch(
            @Nonnull OperationRecoveryClaim claim
    ) {
        if (claim == null) {
            throw new IllegalArgumentException("Recovery claim is required");
        }
        RecoveryHandler handler = handlers.get(claim.operation().kind());
        if (handler == null) {
            throw new IllegalArgumentException(
                    "No public recovery adapter for "
                            + claim.operation().kind()
            );
        }
        return handler.recover(claim);
    }

    private static <T> T payload(
            OperationRecoveryClaim claim,
            Class<T> payloadType
    ) {
        return payloadType.cast(claim.payload().payload());
    }

    @FunctionalInterface
    private interface RecoveryHandler {
        CompletionStage<OperationWorkflowResult> recover(
                OperationRecoveryClaim claim
        );
    }
}
