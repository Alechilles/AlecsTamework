package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.ProfileDataEntryView;
import com.alechilles.alecstamework.api.ProfileDataOperationStatus;
import com.alechilles.alecstamework.api.ProfileDataOperationView;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationEventCodec;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationOutcome;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProfileExtensionOperations;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;

/** Maps replacement operation evidence to the unreleased versioned profile-data API. */
final class ReplacementProfileDataMapper {
    private ReplacementProfileDataMapper() {
    }

    static ProfileDataCompareAndSetResult compareAndSetResult(
            OperationWorkflowResult result,
            String publicIdempotencyKey
    ) {
        if (result == null || result.status() != OperationWorkflowResult.Status.PUBLISHED
                || result.operation() == null) {
            return unavailable("profile-data-operation-incomplete");
        }
        ProfileExtensionMutationOutcome outcome = outcome(result.events());
        ProfileDataOperationView view = operationView(
                new SqliteOperationReader.OperationReadModel(
                        result.operation(),
                        result.events()
                ),
                publicIdempotencyKey
        );
        if (outcome.status() == ProfileExtensionMutationOutcome.Status.APPLIED) {
            ProfileDataEntryView entry = new ProfileDataEntryView(
                    outcome.key().profileId().toString(),
                    outcome.key().namespace(),
                    outcome.key().dataKey(),
                    outcome.revision(),
                    outcome.jsonPayload(),
                    outcome.updatedAtMs()
            );
            return new ProfileDataCompareAndSetResult(
                    ProfileDataCompareAndSetResult.Status.COMMITTED,
                    reason(outcome.status()),
                    view,
                    entry
            );
        }
        return new ProfileDataCompareAndSetResult(
                ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED,
                reason(outcome.status()),
                view,
                null
        );
    }

    static ProfileDataOperationView operationView(
            SqliteOperationReader.OperationReadModel read,
            String publicIdempotencyKey
    ) {
        OperationEnvelope operation = read.operation();
        ProfileExtensionMutation mutation =
                ProfileExtensionMutationDefinition.INSTANCE.decode(
                        operation.payloadJson()
                );
        ProfileExtensionMutationOutcome outcome =
                read.events().isEmpty() ? null : outcome(read.events());
        ProfileDataOperationStatus status = status(operation.phase(), outcome);
        long resultingRevision =
                status == ProfileDataOperationStatus.COMMITTED
                        ? outcome.revision()
                        : ProfileDataOperationView.UNKNOWN_REVISION;
        return new ProfileDataOperationView(
                operation.operationId().value(),
                mutation.key().namespace(),
                publicIdempotencyKey,
                mutation.key().profileId().toString(),
                mutation.key().dataKey(),
                mutation.expectedRevision() == null ? 0 : mutation.expectedRevision(),
                resultingRevision,
                Sha256Hash.ofUtf8(
                        mutation.jsonPayload() == null ? "" : mutation.jsonPayload()
                ).toString(),
                status,
                outcome == null ? phaseReason(operation.phase()) : reason(outcome.status()),
                operation.updatedAtMs()
        );
    }

    private static ProfileDataOperationStatus status(
            OperationPhase phase,
            ProfileExtensionMutationOutcome outcome
    ) {
        if (outcome != null) {
            return outcome.status() == ProfileExtensionMutationOutcome.Status.APPLIED
                    || outcome.status() == ProfileExtensionMutationOutcome.Status.DELETED
                    ? ProfileDataOperationStatus.COMMITTED
                    : ProfileDataOperationStatus.TERMINAL_DENIED;
        }
        return switch (phase) {
            case PREPARED, RETRYABLE -> ProfileDataOperationStatus.PREPARED;
            case LIVE_APPLYING, DURABLE, COMPENSATING, UNKNOWN ->
                    ProfileDataOperationStatus.APPLYING;
            case PUBLISHED, COMPENSATED, FAILED ->
                    ProfileDataOperationStatus.TERMINAL_DENIED;
        };
    }

    private static ProfileExtensionMutationOutcome outcome(
            List<ProjectionEvent> events
    ) {
        ProjectionEvent event = events.stream()
                .filter(candidate -> SqliteProfileExtensionOperations.EVENT_TYPE.equals(
                        candidate.eventType()
                ))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "profile_extension_outcome_missing"
                ));
        return ProfileExtensionMutationEventCodec.decode(
                event.payloadVersion(),
                event.payloadJson()
        );
    }

    private static String reason(ProfileExtensionMutationOutcome.Status status) {
        return switch (status) {
            case APPLIED -> "profile-data-committed";
            case DELETED -> "profile-data-deleted";
            case UNCHANGED -> "profile-data-unchanged";
            case REVISION_MISMATCH -> "profile-data-revision-mismatch";
            case PROFILE_NOT_FOUND -> "profile-data-profile-not-found";
        };
    }

    private static String phaseReason(OperationPhase phase) {
        return "profile-data-" + phase.name().toLowerCase().replace('_', '-');
    }

    private static ProfileDataCompareAndSetResult unavailable(String reason) {
        return new ProfileDataCompareAndSetResult(
                ProfileDataCompareAndSetResult.Status.UNAVAILABLE,
                reason,
                null,
                null
        );
    }
}
