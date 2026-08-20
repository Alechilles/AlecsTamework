package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import javax.annotation.Nonnull;

/** JSON codec for frozen owner-capacity composition evidence. */
public final class OwnerPopulationAdmissionPlanJsonCodec {
    private OwnerPopulationAdmissionPlanJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull OwnerPopulationAdmissionPlan plan
    ) {
        if (plan == null) {
            throw new IllegalArgumentException(
                    "Owner population admission plan is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", plan.profileId().toString());
        if (plan.expectedLifecycleRevision() == null) {
            json.add("expectedLifecycleRevision", null);
        } else {
            json.addProperty(
                    "expectedLifecycleRevision",
                    plan.expectedLifecycleRevision().value()
            );
        }
        JsonArray increases = new JsonArray();
        for (OwnerPopulationAdmissionPlan.LimitIncrease increase
                : plan.increases()) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", increase.scope().kind().name());
            row.addProperty("ownerId", increase.scope().ownerId().toString());
            if (increase.scope().ownerWorldKey() == null) {
                row.add("ownerWorldKey", null);
            } else {
                row.addProperty(
                        "ownerWorldKey", increase.scope().ownerWorldKey()
                );
            }
            row.addProperty("capacityDelta", increase.capacityDelta());
            row.addProperty("snapshottedLimit", increase.snapshottedLimit());
            increases.add(row);
        }
        json.add("increases", increases);
        return json;
    }

    @Nonnull
    public static OwnerPopulationAdmissionPlan decode(
            @Nonnull JsonObject json
    ) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Owner population admission plan JSON is required"
            );
        }
        JsonElement revision = json.get("expectedLifecycleRevision");
        ArrayList<OwnerPopulationAdmissionPlan.LimitIncrease> increases =
                new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("increases")) {
            JsonObject row = element.getAsJsonObject();
            String world = text(row, "ownerWorldKey");
            OwnerPopulationScope scope = OwnerPopulationScope.Kind.valueOf(
                    row.get("kind").getAsString()
            ) == OwnerPopulationScope.Kind.GLOBAL
                    ? OwnerPopulationScope.global(
                    OwnerId.parse(row.get("ownerId").getAsString())
            )
                    : OwnerPopulationScope.perWorld(
                    OwnerId.parse(row.get("ownerId").getAsString()),
                    requireText(world, "ownerWorldKey")
            );
            increases.add(new OwnerPopulationAdmissionPlan.LimitIncrease(
                    scope,
                    row.get("capacityDelta").getAsInt(),
                    row.get("snapshottedLimit").getAsInt()
            ));
        }
        return new OwnerPopulationAdmissionPlan(
                ProfileId.parse(json.get("profileId").getAsString()),
                revision == null || revision.isJsonNull()
                        ? null
                        : new LifecycleRevision(revision.getAsLong()),
                increases
        );
    }

    private static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
