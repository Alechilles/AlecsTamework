package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec and outbox identity for complete group assignment changes. */
public final class PopulationGroupAssignmentChangeCodec {
    public static final int VERSION = 1;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("population_group_assignment_changed");

    private PopulationGroupAssignmentChangeCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nonnull PopulationGroupAssignmentChange change
    ) {
        if (operationId == null || change == null) {
            throw new IllegalArgumentException(
                    "Group assignment event evidence is required"
            );
        }
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                change.profileId().toString(),
                change.after().assignmentRevision(),
                VERSION,
                encode(change),
                change.after().assignedAtMs()
        );
    }

    @Nonnull
    public static String encode(
            @Nonnull PopulationGroupAssignmentChange change
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", change.profileId().toString());
        json.add(
                "before",
                change.before() == null
                        ? null
                        : PopulationGroupAssignmentJsonCodec.encode(
                                change.before()
                        )
        );
        json.add(
                "after",
                PopulationGroupAssignmentJsonCodec.encode(change.after())
        );
        return json.toString();
    }

    @Nonnull
    public static PopulationGroupAssignmentChange decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported group assignment change payload"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        return new PopulationGroupAssignmentChange(
                ProfileId.parse(json.get("profileId").getAsString()),
                json.get("before").isJsonNull()
                        ? null
                        : PopulationGroupAssignmentJsonCodec.decode(
                                json.getAsJsonObject("before")
                        ),
                PopulationGroupAssignmentJsonCodec.decode(
                        json.getAsJsonObject("after")
                )
        );
    }
}
