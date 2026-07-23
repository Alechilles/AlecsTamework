package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;

/** Transaction-local composition of immutable public profile projection evidence. */
final class SqliteCompanionProfileProjectionComposer {
    private SqliteCompanionProfileProjectionComposer() {
    }

    static CompanionProfileProjectionState compose(
            SqlitePersistenceTransactionContext transaction,
            ProfileId profileId
    ) {
        CompanionIdentity identity =
                transaction.identities().findProfile(profileId).orElse(null);
        if (identity == null) {
            return null;
        }
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(profileId)
                .orElseThrow(() -> new IllegalStateException(
                        "profile_projection_lifecycle_missing"
                ));
        return CompanionProfileProjectionState.compose(
                identity,
                transaction.identities().findCurrentAlias(profileId).orElse(null),
                lifecycle,
                transaction.toolLinks().findByProfile(profileId),
                transaction.snapshots().findCurrentByProfile(profileId)
        );
    }

    static ProjectionEventDraft event(
            OperationId operationId,
            CompanionProfileProjectionChange change
    ) {
        return new ProjectionEventDraft(
                operationId,
                CompanionProfileProjectionChangeCodec.EVENT_TYPE,
                CompanionProfileProjectionChangeCodec.aggregateId(change),
                change.sourceRevision(),
                CompanionProfileProjectionChangeCodec.VERSION,
                CompanionProfileProjectionChangeCodec.encode(change),
                change.changedAtMs()
        );
    }
}
