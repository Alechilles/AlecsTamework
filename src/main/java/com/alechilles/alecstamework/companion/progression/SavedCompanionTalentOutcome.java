package com.alechilles.alecstamework.companion.progression;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Durable terminal result for a saved-companion talent mutation. */
public record SavedCompanionTalentOutcome(
        @Nonnull Status status,
        @Nonnull ProfileId profileId,
        @Nonnull SavedCompanionTalentRequest.Action action,
        long lifecycleRevision,
        long updatedAtMs
) {
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("saved_companion_talent_completed");
    public static final int EVENT_VERSION = 1;

    public SavedCompanionTalentOutcome {
        if (status == null || profileId == null || action == null
                || lifecycleRevision < 0) {
            throw new IllegalArgumentException(
                    "Complete saved companion talent outcome is required"
            );
        }
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }

    /** Returns whether a completed workflow durably applied its requested talent change. */
    public static boolean isApplied(@javax.annotation.Nullable com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult result) {
        if (result == null || result.status()
                != com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult.Status.PUBLISHED) {
            return false;
        }
        for (var event : result.events()) {
            if (!SavedCompanionTalentOutcome.EVENT_TYPE.equals(
                    event.eventType())) {
                continue;
            }
            try {
                return SavedCompanionTalentOutcome.decode(
                        event.payloadVersion(), event.payloadJson()).applied();
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    /** Encodes stable terminal evidence carried by the operation outbox. */
    @Nonnull
    public static String encode(@Nonnull SavedCompanionTalentOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Saved talent outcome is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("status", outcome.status().name());
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty("action", outcome.action().name());
        json.addProperty("lifecycleRevision", outcome.lifecycleRevision());
        json.addProperty("updatedAtMs", outcome.updatedAtMs());
        return json.toString();
    }

    /** Decodes exact version-one terminal evidence. */
    @Nonnull
    public static SavedCompanionTalentOutcome decode(
            int eventVersion,
            @Nonnull String payloadJson
    ) {
        if (eventVersion != EVENT_VERSION) {
            throw new IllegalArgumentException(
                    "saved_talent_outcome_event_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new SavedCompanionTalentOutcome(
                Status.valueOf(json.get("status").getAsString()),
                ProfileId.parse(json.get("profileId").getAsString()),
                SavedCompanionTalentRequest.Action.valueOf(
                        json.get("action").getAsString()),
                json.get("lifecycleRevision").getAsLong(),
                json.get("updatedAtMs").getAsLong()
        );
    }

    /** Stable domain results; denials are terminal published outcomes. */
    public enum Status {
        APPLIED,
        PROFILE_NOT_FOUND,
        OWNER_MISMATCH,
        PROFILE_NOT_OFFLINE,
        LIFECYCLE_STALE,
        SNAPSHOT_STALE,
        SNAPSHOT_UNSUPPORTED,
        TALENTS_DISABLED,
        IDENTITY_NOT_FOUND,
        LEVEL_UNAVAILABLE,
        CONFIG_STALE,
        PURCHASE_REJECTED,
        RESET_REJECTED
    }
}
