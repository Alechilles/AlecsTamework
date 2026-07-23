package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleJsonCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import javax.annotation.Nonnull;

/** Shared JSON codec for exact population-group transition admission evidence. */
public final class PopulationGroupTransitionAdmissionJsonCodec {
    private PopulationGroupTransitionAdmissionJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull PopulationGroupTransitionAdmissionRequest admission
    ) {
        if (admission == null) {
            throw new IllegalArgumentException(
                    "Group transition admission is required"
            );
        }
        JsonObject json = new JsonObject();
        json.add(
                "before",
                CompanionLifecycleJsonCodec.encode(admission.before())
        );
        json.add(
                "after",
                CompanionLifecycleJsonCodec.encode(admission.after())
        );
        json.addProperty(
                "expectedAssignmentRevision",
                admission.expectedAssignmentRevision()
        );
        json.addProperty(
                "expectedPolicyRevision",
                admission.expectedPolicyRevision()
        );
        JsonArray policies = new JsonArray();
        for (PopulationGroupPolicy policy : admission.policies()) {
            JsonObject row = new JsonObject();
            row.addProperty("groupId", policy.groupId());
            row.addProperty("scope", policy.scope().name());
            row.addProperty(
                    "maxOwnedPerOwner", policy.maxOwnedPerOwner()
            );
            row.addProperty(
                    "maxActivePerOwner", policy.maxActivePerOwner()
            );
            row.addProperty(
                    "policyRevision", policy.policyRevision()
            );
            policies.add(row);
        }
        json.add("policies", policies);
        json.addProperty(
                "requestedAtMs", admission.requestedAtMs()
        );
        return json;
    }

    @Nonnull
    public static PopulationGroupTransitionAdmissionRequest decode(
            @Nonnull JsonObject json
    ) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Group transition admission JSON is required"
            );
        }
        ArrayList<PopulationGroupPolicy> policies = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("policies")) {
            JsonObject row = element.getAsJsonObject();
            policies.add(new PopulationGroupPolicy(
                    row.get("groupId").getAsString(),
                    PopulationGroupScope.valueOf(
                            row.get("scope").getAsString()
                    ),
                    row.get("maxOwnedPerOwner").getAsInt(),
                    row.get("maxActivePerOwner").getAsInt(),
                    row.get("policyRevision").getAsLong()
            ));
        }
        return new PopulationGroupTransitionAdmissionRequest(
                CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("before")
                ),
                CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("after")
                ),
                json.get("expectedAssignmentRevision").getAsLong(),
                json.get("expectedPolicyRevision").getAsLong(),
                policies,
                json.get("requestedAtMs").getAsLong()
        );
    }
}
