package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationEventCodec;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationOutcome;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.ArrayList;
import java.util.List;

/** Commits a dead companion as provisioned and dormant. */
final class SqliteDormantRestorationCommit {
    private SqliteDormantRestorationCommit() {
    }

    static List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionRestorationRequest restoration,
            long restoredAtMs
    ) {
        CompanionLifecycle dead =
                SqliteCompanionRestorationPreparation.requireRestorableSource(
                        transaction, restoration
                );
        ProvisioningRecord provisioning =
                SqliteCompanionRestorationPreparation.requireProvisioning(
                        transaction, restoration
                );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, restoration.profileId()
                );
        CompanionLifecycle dormant = new CompanionLifecycle(
                restoration.profileId(),
                dead.ownerId(),
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        provisioning.origin().stableKey()
                ),
                dead.revision().next(),
                null,
                restoredAtMs,
                dead.lastReconciledGeneration(),
                dead.quarantineIncidentId(),
                dead.ownerWorldKey()
        );
        if (!transaction.lifecycles().transition(
                new LifecycleTransition(dead.revision(), null, dormant)
        ).applied()) {
            throw new IllegalStateException(
                    "restoration_dormant_lifecycle_rejected"
            );
        }
        return events(
                transaction,
                operation,
                restoration,
                dead,
                dormant,
                before,
                restoredAtMs
        );
    }

    private static List<ProjectionEventDraft> events(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionRestorationRequest restoration,
            CompanionLifecycle dead,
            CompanionLifecycle dormant,
            CompanionProfileProjectionState before,
            long restoredAtMs
    ) {
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, restoration.profileId()
                );
        CompanionRestorationOutcome outcome =
                new CompanionRestorationOutcome(
                        restoration.profileId(),
                        restoration.sourceSnapshot().snapshotId(),
                        LifecycleState.PROVISIONED_DORMANT,
                        null,
                        null,
                        dormant.revision(),
                        null,
                        restoredAtMs
                );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        restoration.profileId(),
                        dormant.revision().value(),
                        before,
                        after,
                        restoredAtMs
                );
        ArrayList<ProjectionEventDraft> events = new ArrayList<>(4);
        events.add(new ProjectionEventDraft(
                operation.operationId(),
                SqliteCompanionRestorationOperations.EVENT_TYPE,
                "restoration-result:" + outcome.profileId(),
                dormant.revision().value(),
                CompanionRestorationEventCodec.VERSION,
                CompanionRestorationEventCodec.encode(outcome),
                restoredAtMs
        ));
        SqliteProvisionedCompanionLifecycleEvents.revival(
                transaction,
                operation.operationId(),
                dormant,
                restoration.expectedLifecycleRevision(),
                null,
                restoredAtMs
        ).ifPresent(events::add);
        events.add(SqliteCompanionProfileProjectionComposer.event(
                operation.operationId(), change
        ));
        events.add(CompanionLifecycleProjectionChangeCodec.draft(
                operation.operationId(), dead, dormant, restoredAtMs
        ));
        return List.copyOf(events);
    }
}
