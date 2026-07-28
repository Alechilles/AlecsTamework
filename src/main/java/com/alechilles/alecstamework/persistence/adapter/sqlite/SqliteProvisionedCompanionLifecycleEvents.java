package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionDeathOutcome;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionLifecycleEventCodec;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionRevivalOutcome;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Authors optional semantic lifecycle events from canonical transaction-local
 * provisioning, identity, and lifecycle facts.
 */
final class SqliteProvisionedCompanionLifecycleEvents {
    private SqliteProvisionedCompanionLifecycleEvents() {
    }

    static Optional<ProjectionEventDraft> death(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            CompanionLifecycle before,
            CompanionLifecycle after,
            NpcAlias lastAlias,
            long diedAtMs
    ) {
        if (after.state() != LifecycleState.DEAD_REVIVABLE) {
            return Optional.empty();
        }
        ProvisioningRecord provisioning = transaction.provisioning()
                .findByProfile(after.profileId())
                .orElse(null);
        if (provisioning == null) {
            return Optional.empty();
        }
        CompanionIdentity identity = requireIdentity(
                transaction, after
        );
        return Optional.of(
                ProvisionedCompanionLifecycleEventCodec.deathDraft(
                        operationId,
                        new ProvisionedCompanionDeathOutcome(
                                provisioning.origin(),
                                after.profileId(),
                                requireOwner(after),
                                requireRole(identity),
                                lastAlias,
                                after.state(),
                                CompanionProvisioningProjectionStatus
                                        .UNAVAILABLE,
                                before.revision(),
                                after.revision(),
                                diedAtMs
                        )
                )
        );
    }

    static Optional<ProjectionEventDraft> revival(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            CompanionLifecycle after,
            LifecycleRevision oldRevision,
            @Nullable NpcAlias newAlias,
            long revivedAtMs
    ) {
        ProvisioningRecord provisioning = transaction.provisioning()
                .findByProfile(after.profileId())
                .orElse(null);
        if (provisioning == null) {
            return Optional.empty();
        }
        CompanionIdentity identity = requireIdentity(
                transaction, after
        );
        return Optional.of(
                ProvisionedCompanionLifecycleEventCodec.revivalDraft(
                        operationId,
                        new ProvisionedCompanionRevivalOutcome(
                                provisioning.origin(),
                                after.profileId(),
                                requireOwner(after),
                                requireRole(identity),
                                newAlias,
                                after.state(),
                                after.state() == LifecycleState.ACTIVE
                                        ? CompanionProvisioningProjectionStatus
                                        .ACTIVE
                                        : CompanionProvisioningProjectionStatus
                                        .NOT_REQUESTED,
                                oldRevision,
                                after.revision(),
                                revivedAtMs
                        )
                )
        );
    }

    private static CompanionIdentity requireIdentity(
            SqlitePersistenceTransactionContext transaction,
            CompanionLifecycle lifecycle
    ) {
        return transaction.identities()
                .findProfile(lifecycle.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "provisioned_lifecycle_identity_missing"
                ));
    }

    private static com.alechilles.alecstamework.companion.identity.OwnerId
    requireOwner(CompanionLifecycle lifecycle) {
        if (lifecycle.ownerId() == null) {
            throw new IllegalStateException(
                    "provisioned_lifecycle_owner_missing"
            );
        }
        return lifecycle.ownerId();
    }

    private static String requireRole(CompanionIdentity identity) {
        if (identity.roleId() == null) {
            throw new IllegalStateException(
                    "provisioned_lifecycle_role_missing"
            );
        }
        return identity.roleId();
    }
}
