package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
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
        json.add("before", assignment(change.before()));
        json.add("after", assignment(change.after()));
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
                readAssignment(json.get("before")),
                readAssignment(json.get("after"))
        );
    }

    private static JsonElement assignment(
            PopulationGroupAssignment value
    ) {
        if (value == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", value.profileId().toString());
        nullable(json, "roleId", value.roleId());
        JsonArray memberships = new JsonArray();
        for (PopulationGroupMembership membership : value.memberships()) {
            JsonObject row = new JsonObject();
            row.addProperty("groupId", membership.groupId());
            row.addProperty("scope", membership.scope().name());
            memberships.add(row);
        }
        json.add("memberships", memberships);
        json.addProperty("policyRevision", value.policyRevision());
        json.addProperty(
                "sourceMetadataRevision",
                value.sourceMetadataRevision()
        );
        json.addProperty(
                "sourceLifecycleRevision",
                value.sourceLifecycleRevision().value()
        );
        json.addProperty(
                "assignmentRevision",
                value.assignmentRevision()
        );
        json.addProperty("assignedAtMs", value.assignedAtMs());
        return json;
    }

    private static PopulationGroupAssignment readAssignment(
            JsonElement element
    ) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        ArrayList<PopulationGroupMembership> memberships =
                new ArrayList<>();
        for (JsonElement item : json.getAsJsonArray("memberships")) {
            JsonObject row = item.getAsJsonObject();
            memberships.add(new PopulationGroupMembership(
                    row.get("groupId").getAsString(),
                    PopulationGroupScope.valueOf(
                            row.get("scope").getAsString()
                    )
            ));
        }
        return new PopulationGroupAssignment(
                ProfileId.parse(json.get("profileId").getAsString()),
                text(json, "roleId"),
                memberships,
                json.get("policyRevision").getAsLong(),
                json.get("sourceMetadataRevision").getAsLong(),
                new LifecycleRevision(
                        json.get("sourceLifecycleRevision").getAsLong()
                ),
                json.get("assignmentRevision").getAsLong(),
                json.get("assignedAtMs").getAsLong()
        );
    }

    private static void nullable(
            JsonObject json,
            String name,
            String value
    ) {
        if (value == null) {
            json.add(name, null);
        } else {
            json.addProperty(name, value);
        }
    }

    private static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }
}
