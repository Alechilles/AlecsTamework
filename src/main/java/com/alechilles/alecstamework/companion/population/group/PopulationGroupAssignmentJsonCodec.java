package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import javax.annotation.Nonnull;

/** Shared validating JSON translation for one complete group assignment. */
public final class PopulationGroupAssignmentJsonCodec {
    private PopulationGroupAssignmentJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull PopulationGroupAssignment value
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Population group assignment is required"
            );
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

    @Nonnull
    public static PopulationGroupAssignment decode(
            @Nonnull JsonObject json
    ) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Population group assignment JSON is required"
            );
        }
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

