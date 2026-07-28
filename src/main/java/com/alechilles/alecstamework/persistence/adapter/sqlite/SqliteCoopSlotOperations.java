package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistrationDefinition;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import javax.annotation.Nonnull;

/** Database-only structural coop registration through the shared operation protocol. */
public final class SqliteCoopSlotOperations {
    public static final String FEATURE_SCOPE = "coop_slot_registration";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("coop_slot_registered");

    private final SqliteDatabaseOperationCoordinator workflow;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCoopSlotOperations(
            @Nonnull SqliteDatabaseOperationCoordinator workflow,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (workflow == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Coop slot dependencies are required");
        }
        this.workflow = workflow;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Registers or replays one exact normalized slot. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CoopSlotRegistration registration
    ) {
        if (operationId == null || idempotencyKey == null || registration == null) {
            throw new IllegalArgumentException("Complete coop slot registration is required");
        }
        OperationRequest<CoopSlotRegistration> request = new OperationRequest<>(
                operationId,
                idempotencyKey,
                registration,
                FEATURE_SCOPE,
                null,
                List.of(OperationScope.coop(
                        registration.slot().key().toString()
                )),
                registration.requestedAtMs()
        );
        return workflow.execute(
                CoopSlotRegistrationDefinition.INSTANCE,
                request,
                (transaction, operation) -> {
                    CoopSlot slot = requireApplied(
                            transaction.coops().registerSlot(registration.slot())
                    );
                    return List.of(new ProjectionEventDraft(
                            operation.operationId(),
                            EVENT_TYPE,
                            "coop-slot:" + slot.key(),
                            slot.residencyRevision(),
                            1,
                            CoopSlotRegistrationDefinition.INSTANCE.encode(
                                    registration
                            ),
                            registration.requestedAtMs()
                    ));
                },
                requiredConsumers
        );
    }

    private CoopSlot requireApplied(PersistenceMutationResult<CoopSlot> result) {
        if (result == null || !result.applied()) {
            throw new IllegalStateException(
                    "coop_slot_registration_"
                            + (result == null
                            ? "null"
                            : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }
}
