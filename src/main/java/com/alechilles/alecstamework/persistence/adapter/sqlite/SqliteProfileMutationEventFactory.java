package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationEventCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationOutcome;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds the profile, lifecycle, and operation-result events for one profile mutation.
 */
final class SqliteProfileMutationEventFactory {
    private SqliteProfileMutationEventFactory() {
    }

    @Nonnull
    static List<ProjectionEventDraft> create(
            @Nonnull OperationId operationId,
            @Nonnull CompanionProfileMutationOutcome outcome,
            @Nullable CompanionProfileProjectionChange profileChange,
            @Nullable CompanionLifecycleProjectionChange lifecycleChange
    ) {
        ArrayList<ProjectionEventDraft> events = new ArrayList<>();
        events.add(outcomeEvent(operationId, outcome));
        if (profileChange != null) {
            events.add(SqliteCompanionProfileProjectionComposer.event(
                    operationId,
                    profileChange
            ));
        }
        if (lifecycleChange != null) {
            events.add(CompanionLifecycleProjectionChangeCodec.draft(
                    operationId,
                    lifecycleChange.before(),
                    lifecycleChange.after(),
                    outcome.updatedAtMs()
            ));
        }
        return List.copyOf(events);
    }

    private static ProjectionEventDraft outcomeEvent(
            OperationId operationId,
            CompanionProfileMutationOutcome outcome
    ) {
        return new ProjectionEventDraft(
                operationId,
                SqliteCompanionProfileOperations.EVENT_TYPE,
                "profile-mutation-result:" + outcome.profileId(),
                outcome.metadataRevision(),
                CompanionProfileMutationEventCodec.VERSION,
                CompanionProfileMutationEventCodec.encode(outcome),
                outcome.updatedAtMs()
        );
    }
}
