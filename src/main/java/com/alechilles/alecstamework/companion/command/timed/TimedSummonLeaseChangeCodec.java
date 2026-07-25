package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Self-contained outbox evidence for one canonical timed lease change. */
public final class TimedSummonLeaseChangeCodec {
    public static final int VERSION = 1;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("timed_summon_lease_changed");

    private TimedSummonLeaseChangeCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nonnull TimedSummonLeaseChange change,
            long changedAtMs
    ) {
        if (operationId == null || change == null) {
            throw new IllegalArgumentException(
                    "Timed summon lease event evidence is required"
            );
        }
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                change.after().profileId().toString(),
                change.after().leaseRevision(),
                VERSION,
                encode(change),
                changedAtMs
        );
    }

    @Nonnull
    public static String encode(@Nonnull TimedSummonLeaseChange change) {
        JsonObject json = new JsonObject();
        if (change.before() == null) {
            json.add("before", com.google.gson.JsonNull.INSTANCE);
        } else {
            json.add(
                    "before",
                    TimedSummonLeaseJsonCodec.encode(change.before())
            );
        }
        json.add(
                "after",
                TimedSummonLeaseJsonCodec.encode(change.after())
        );
        return json.toString();
    }

    @Nonnull
    public static TimedSummonLeaseChange decode(@Nonnull String payload) {
        JsonObject json = JsonParser.parseString(payload)
                .getAsJsonObject();
        return new TimedSummonLeaseChange(
                json.get("before").isJsonNull()
                        ? null
                        : TimedSummonLeaseJsonCodec.decode(
                                json.getAsJsonObject("before")
                        ),
                TimedSummonLeaseJsonCodec.decode(
                        json.getAsJsonObject("after")
                )
        );
    }
}

