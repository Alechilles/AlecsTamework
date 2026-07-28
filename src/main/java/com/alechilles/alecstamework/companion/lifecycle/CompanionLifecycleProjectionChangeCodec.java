package com.alechilles.alecstamework.companion.lifecycle;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Versioned JSON codec for self-contained canonical lifecycle projection events. */
public final class CompanionLifecycleProjectionChangeCodec {
    public static final int PAYLOAD_VERSION = 1;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_lifecycle_changed");

    private CompanionLifecycleProjectionChangeCodec() {
    }

    /** Encodes one lifecycle change without requiring a projection-time database read. */
    @Nonnull
    public static String encode(
            @Nonnull CompanionLifecycleProjectionChange change
    ) {
        if (change == null) {
            throw new IllegalArgumentException(
                    "Lifecycle projection change is required"
            );
        }
        JsonObject json = new JsonObject();
        if (change.before() == null) {
            json.add("before", null);
        } else {
            json.add(
                    "before",
                    CompanionLifecycleJsonCodec.encode(change.before())
            );
        }
        json.add(
                "after",
                CompanionLifecycleJsonCodec.encode(change.after())
        );
        return json.toString();
    }

    /** Decodes only the one supported positive payload version. */
    @Nonnull
    public static CompanionLifecycleProjectionChange decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != PAYLOAD_VERSION
                || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported lifecycle projection payload"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        CompanionLifecycle before = json.get("before").isJsonNull()
                ? null
                : CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("before")
                );
        return new CompanionLifecycleProjectionChange(
                before,
                CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("after")
                )
        );
    }

    /** Builds the exact outbox draft for one committed lifecycle revision. */
    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nullable CompanionLifecycle before,
            @Nonnull CompanionLifecycle after,
            long changedAtMs
    ) {
        CompanionLifecycleProjectionChange change =
                new CompanionLifecycleProjectionChange(before, after);
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                after.profileId().toString(),
                after.revision().value(),
                PAYLOAD_VERSION,
                encode(change),
                changedAtMs
        );
    }
}
