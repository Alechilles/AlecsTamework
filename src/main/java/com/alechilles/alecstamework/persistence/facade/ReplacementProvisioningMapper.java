package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.CompanionProvisioningOperationStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningOperationView;
import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.ProvisionedCompanionView;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import javax.annotation.Nonnull;

/** Value-only mapping between replacement provisioning evidence and v0.9 API views. */
final class ReplacementProvisioningMapper {
    private ReplacementProvisioningMapper() {
    }

    @Nonnull
    static ProvisionedCompanionView companion(
            @Nonnull ProvisioningRecord record,
            @Nonnull CompanionProfileReadModel profile
    ) {
        LifecycleState lifecycle = profile.lifecycle().state();
        NpcAlias alias = lifecycle == LifecycleState.ACTIVE
                ? profile.currentAlias() == null
                ? null
                : profile.currentAlias().alias()
                : null;
        return new ProvisionedCompanionView(
                record.creationOperationId().value(),
                record.origin().callerNamespace(),
                record.origin().callerKey(),
                record.profileId().toString(),
                profile.lifecycle().ownerId().value(),
                profile.identity().roleId(),
                lifecycle(lifecycle),
                projection(lifecycle),
                alias == null ? null : alias.value(),
                profile.lifecycle().revision().value(),
                Math.max(
                        profile.identity().updatedAtMs(),
                        profile.lifecycle().stateChangedAtMs()
                )
        );
    }

    @Nonnull
    static CompanionProvisioningOperationView operation(
            @Nonnull OperationEnvelope operation
    ) {
        CompanionProvisioningRequest request =
                CompanionProvisioningDefinition.INSTANCE.decode(
                        operation.payloadJson()
                );
        return new CompanionProvisioningOperationView(
                operation.operationId().value(),
                request.origin().callerNamespace(),
                request.origin().callerKey(),
                request.correlationId(),
                operationStatus(operation.phase()),
                reason(operation),
                request.origin().profileId().toString(),
                request.lifecycle().ownerId().value(),
                request.identity().roleId(),
                PopulationCompanionLifecycle.PROVISIONED_DORMANT,
                operation.phase() == OperationPhase.PUBLISHED
                        ? CompanionProvisioningProjectionStatus.NOT_REQUESTED
                        : CompanionProvisioningProjectionStatus.PENDING,
                request.lifecycle().revision().value(),
                operation.updatedAtMs()
        );
    }

    @Nonnull
    static PopulationCompanionLifecycle lifecycle(
            @Nonnull LifecycleState lifecycle
    ) {
        return switch (lifecycle) {
            case ACTIVE -> PopulationCompanionLifecycle.ACTIVE;
            case UNLOADED -> PopulationCompanionLifecycle.UNLOADED;
            case CAPTURED -> PopulationCompanionLifecycle.CAPTURED;
            case COOP -> PopulationCompanionLifecycle.COOP;
            case ROSTER_STORED -> PopulationCompanionLifecycle.ROSTER_STORED;
            case PROVISIONED_DORMANT ->
                    PopulationCompanionLifecycle.PROVISIONED_DORMANT;
            case DEAD_REVIVABLE ->
                    PopulationCompanionLifecycle.DEAD_REVIVABLE;
            case LOST -> PopulationCompanionLifecycle.LOST;
            case RELEASED -> PopulationCompanionLifecycle.RELEASED;
            case UNRESOLVED -> PopulationCompanionLifecycle.UNKNOWN_DORMANT;
        };
    }

    @Nonnull
    static CompanionProvisioningProjectionStatus projection(
            @Nonnull LifecycleState lifecycle
    ) {
        return lifecycle == LifecycleState.ACTIVE
                ? CompanionProvisioningProjectionStatus.ACTIVE
                : lifecycle == LifecycleState.PROVISIONED_DORMANT
                ? CompanionProvisioningProjectionStatus.NOT_REQUESTED
                : CompanionProvisioningProjectionStatus.UNAVAILABLE;
    }

    private static CompanionProvisioningOperationStatus operationStatus(
            OperationPhase phase
    ) {
        return switch (phase) {
            case PREPARED -> CompanionProvisioningOperationStatus.PREPARED;
            case LIVE_APPLYING ->
                    CompanionProvisioningOperationStatus.APPLYING;
            case DURABLE -> CompanionProvisioningOperationStatus.COMMITTED;
            case PUBLISHED ->
                    CompanionProvisioningOperationStatus.DORMANT_COMMITTED;
            case COMPENSATING, RETRYABLE, UNKNOWN ->
                    CompanionProvisioningOperationStatus.PARTIAL_DORMANT;
            case COMPENSATED, FAILED ->
                    CompanionProvisioningOperationStatus.TERMINAL_DENIED;
        };
    }

    private static String reason(OperationEnvelope operation) {
        if (operation.failureCode() != null) {
            return operation.failureCode();
        }
        return "provisioning-" + operation.phase().name()
                .toLowerCase(java.util.Locale.ROOT);
    }
}
